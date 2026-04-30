/**
 * High-performance Sieve of Eratosthenes -- finds all primes up to 1 billion.
 *
 * Optimisations over the base port:
 *   1. Odd-only bit-packed sieve  (as before)
 *   2. Segmented sieve            (as before)
 *   3. CACHE_LEVEL tuning         -- segment sized to fit in L1 / L2 / L3
 *   4. Word-at-a-time harvest     -- processes 64 bits with ctz instead of bit-by-bit
 *   5. AVX2 harvest fast-path     -- skips all-composite 256-bit chunks in one test
 *   6. AVX2 segment zeroing       -- 256-bit stores instead of memset
 *   7. Large-prime fast-path      -- primes p > SEG_BITS/2 mark <=1 bit per segment;
 *                                    they are split off to avoid branch overhead
 *   8. Fast integer-to-ASCII      -- avoids printf/sprintf in the hot output loop
 *   9. 32-byte-aligned segment    -- required for AVX2 aligned loads/stores
 *
 * Compile:
 *   GCC / Clang:
 *     g++ -O3 -std=c++17 -mavx2 -o PrimeSieve PrimeSieve.cpp
 *
 *   MSVC (Developer Command Prompt):
 *     cl /O2 /std:c++17 /arch:AVX2 /EHsc PrimeSieve.cpp
 *
 *   CMake (both compilers):
 *     cmake -B build && cmake --build build --config Release
 *
 * Cache tuning (define before compile, or via CMake -D):
 *   -DCACHE_LEVEL=1   L1 sieve  ( 16 KB of sieve bits -- ~128K odd positions)
 *   -DCACHE_LEVEL=2   L2 sieve  (256 KB of sieve bits -- ~2M  odd positions) [default]
 *   -DCACHE_LEVEL=3   L3 sieve  (  4 MB of sieve bits -- ~32M odd positions)
 */

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <chrono>
#include <vector>
#include <algorithm>
#include <string>

// ---------------------------------------------------------------------------
// AVX2 availability
// ---------------------------------------------------------------------------
#if defined(__AVX2__)
#  include <immintrin.h>
#  define HAVE_AVX2 1
#else
#  define HAVE_AVX2 0
#endif

// ---------------------------------------------------------------------------
// Cross-compiler portability
// ---------------------------------------------------------------------------
#if defined(_MSC_VER)
#  include <intrin.h>
#  pragma intrinsic(_BitScanForward64)
   static inline int ctz64(uint64_t x)
   {
       unsigned long idx;
       _BitScanForward64(&idx, x);
       return static_cast<int>(idx);
   }
#  define FORCE_INLINE __forceinline
#else
   static inline int ctz64(uint64_t x) { return __builtin_ctzll(x); }
#  define FORCE_INLINE __attribute__((always_inline)) inline
#endif

// ---------------------------------------------------------------------------
// Segment size (cache-level tuning)
// ---------------------------------------------------------------------------
#ifndef CACHE_LEVEL
#  define CACHE_LEVEL 2
#endif

#if CACHE_LEVEL == 1
    // ~16 KB of sieve bits -> 131,072 odd positions -> spans ~262K integers (fits in L1)
    static constexpr int SEG_BITS = 16  * 1024 * 8;
#elif CACHE_LEVEL == 3
    // ~4 MB of sieve bits -> 33,554,432 odd positions (fits in most L3 caches)
    static constexpr int SEG_BITS = 4 * 1024 * 1024 * 8;
#else
    // ~256 KB of sieve bits -> 2,097,152 odd positions (fits in L2) [default]
    static constexpr int SEG_BITS = 256 * 1024 * 8;
#endif

static constexpr int SEG_WORDS = SEG_BITS / 64;          // uint64_t words per segment
static constexpr int LIMIT     = 1'000'000'000;

// Large-prime threshold: primes p where 2p > SEG_BITS mark at most one bit per
// segment -- they are processed in a separate, branchless path.
static constexpr int LARGE_P_THRESHOLD = SEG_BITS / 2;

// ---------------------------------------------------------------------------
// Static storage  (BSS/data -- does not consume stack)
// ---------------------------------------------------------------------------

// 32-byte aligned for AVX2 loads/stores
alignas(32) static uint64_t seg[SEG_WORDS];

static char fileBuf [8  * 1024 * 1024];  // setvbuf OS buffer (8 MB)
static char writeBuf[2  * 1024 * 1024];  // batch write buffer (2 MB)
static int  writeBufLen = 0;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static double nanosToMs(long long ns) { return ns / 1'000'000.0; }

static std::string fmtComma(long long n)
{
    std::string s = std::to_string(n);
    int pos = static_cast<int>(s.length()) - 3;
    while (pos > 0) { s.insert(static_cast<std::string::size_type>(pos), ","); pos -= 3; }
    return s;
}

// Write a positive integer into buf[]; returns number of chars written (no NUL).
static FORCE_INLINE int itoaBuf(long long n, char* buf)
{
    char tmp[20];
    int  len = 0;
    do { tmp[len++] = static_cast<char>('0' + n % 10); n /= 10; } while (n);
    for (int i = 0; i < len; i++) buf[i] = tmp[len - 1 - i];
    return len;
}

// ---------------------------------------------------------------------------
// Buffered prime output
// ---------------------------------------------------------------------------
static FILE* gOutFile = nullptr;

static void flushWriteBuf()
{
    if (writeBufLen > 0) {
        std::fwrite(writeBuf, 1, static_cast<std::size_t>(writeBufLen), gOutFile);
        writeBufLen = 0;
    }
}

// Append one prime to writeBuf, preceded by a space (or not, if first=true).
static FORCE_INLINE void appendPrime(long long n, bool first = false)
{
    if (writeBufLen >= static_cast<int>(sizeof(writeBuf)) - 12)
        flushWriteBuf();
    if (!first) writeBuf[writeBufLen++] = ' ';
    writeBufLen += itoaBuf(n, writeBuf + writeBufLen);
}

// ---------------------------------------------------------------------------
// Segment zeroing (AVX2 path + scalar fallback)
// ---------------------------------------------------------------------------
static void zeroSeg(int longsNeeded)
{
#if HAVE_AVX2
    __m256i z   = _mm256_setzero_si256();
    int     avxN = longsNeeded / 4;
    for (int i = 0; i < avxN; i++)
        _mm256_store_si256(reinterpret_cast<__m256i*>(seg + i * 4), z);
    // tail (0-3 words)
    std::memset(seg + avxN * 4, 0,
                static_cast<std::size_t>(longsNeeded - avxN * 4) * sizeof(uint64_t));
#else
    std::memset(seg, 0, static_cast<std::size_t>(longsNeeded) * sizeof(uint64_t));
#endif
}

// ---------------------------------------------------------------------------
// Harvest: scan bit-packed segment and emit prime values.
//
// A 0-bit means prime, 1-bit means composite.
// We invert each word (primes become 1s) then use ctz to find them.
// AVX2 fast-path: skip all-composite 32-byte chunks with a single VPTEST.
// ---------------------------------------------------------------------------
static long long harvestSeg(long long low, int bitsInSeg, int longsNeeded,
                             long long& primeCount)
{
    // Mask for the last (possibly partial) word -- zeros out bits beyond bitsInSeg
    int lastValidBits = bitsInSeg - (longsNeeded - 1) * 64;
    uint64_t lastMask = (lastValidBits == 64)
                        ? UINT64_C(0xFFFFFFFFFFFFFFFF)
                        : (UINT64_C(1) << lastValidBits) - 1u;

    int w = 0;

#if HAVE_AVX2
    // Process 4 words (256 bits) at a time.
    // _mm256_testc_si256(a, ones) == 1 when (a & ones) == ones, i.e. all bits 1.
    // That means all 256 positions are composite -- safe to skip the chunk.
    __m256i vones = _mm256_set1_epi64x(static_cast<long long>(UINT64_C(0xFFFFFFFFFFFFFFFF)));

    for (; w + 4 <= longsNeeded; w += 4) {
        __m256i chunk = _mm256_load_si256(reinterpret_cast<const __m256i*>(seg + w));
        if (_mm256_testc_si256(chunk, vones)) continue; // all composite, fast skip

        // At least one prime in this 256-bit block -- extract and process
        for (int i = 0; i < 4; i++) {
            int word = w + i;
            uint64_t bits = ~seg[word];
            if (word == longsNeeded - 1) bits &= lastMask;
            long long base = low + 2LL * (word * 64);
            while (bits) {
                int bit = ctz64(bits);
                appendPrime(base + 2LL * bit);
                primeCount++;
                bits &= bits - 1; // clear lowest set bit
            }
        }
    }
#endif

    // Scalar tail (or full loop without AVX2)
    for (; w < longsNeeded; w++) {
        uint64_t bits = ~seg[w];
        if (w == longsNeeded - 1) bits &= lastMask;
        long long base = low + 2LL * (w * 64);
        while (bits) {
            int bit = ctz64(bits);
            appendPrime(base + 2LL * bit);
            primeCount++;
            bits &= bits - 1;
        }
    }

    return primeCount;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
int main()
{
    using Clock = std::chrono::high_resolution_clock;
    auto totalStart = Clock::now();

    printf("=== Sieve of Eratosthenes -- primes to %s ===\n", fmtComma(LIMIT).c_str());
    printf("    Segment: %d KB  |  Cache level: %d  |  AVX2: %s\n\n",
           (SEG_BITS / 8) / 1024, CACHE_LEVEL, HAVE_AVX2 ? "yes" : "no");

    /* ------------------------------------------------------------------
     * Phase 1: Plain sieve to find all primes up to sqrt(LIMIT).
     * sqrt(1e9) ~= 31,623 -- tiny range, very fast.
     * ------------------------------------------------------------------ */
    auto t0 = Clock::now();

    int sqrtLimit = static_cast<int>(std::ceil(std::sqrt(static_cast<double>(LIMIT))));

    std::vector<bool> smallComp(sqrtLimit + 1, false);
    smallComp[0] = smallComp[1] = true;
    for (int i = 2; static_cast<long long>(i) * i <= sqrtLimit; i++)
        if (!smallComp[i])
            for (int j = i * i; j <= sqrtLimit; j += i)
                smallComp[j] = true;

    std::vector<int> smallPrimes;
    smallPrimes.reserve(3'500);
    for (int i = 2; i <= sqrtLimit; i++)
        if (!smallComp[i])
            smallPrimes.push_back(i);

    // Split into small (p <= LARGE_P_THRESHOLD) and large (p > LARGE_P_THRESHOLD).
    // Large primes mark at most one bit per segment, so they are handled separately
    // to avoid the inner-while overhead.
    int splitIdx = 1; // index where large primes begin (skip p=2 at [0])
    while (splitIdx < static_cast<int>(smallPrimes.size()) &&
           smallPrimes[splitIdx] <= LARGE_P_THRESHOLD)
        splitIdx++;
    // smallPrimes[1..splitIdx-1] = "medium" odd primes  (many hits per segment)
    // smallPrimes[splitIdx..]    = "large"  odd primes  (0 or 1 hit per segment)

    auto t1 = Clock::now();
    printf("Phase 1 -- small sieve up to %s:     found %s small primes   [%.3f ms]\n",
           fmtComma(sqrtLimit).c_str(),
           fmtComma(static_cast<long long>(smallPrimes.size())).c_str(),
           nanosToMs(std::chrono::duration_cast<std::chrono::nanoseconds>(t1-t0).count()));

    /* ------------------------------------------------------------------
     * Phase 2: Open output file with 8 MB kernel buffer.
     * ------------------------------------------------------------------ */
    auto t2 = Clock::now();

    gOutFile = std::fopen("primes.txt", "wb");
    if (!gOutFile) {
        std::fprintf(stderr, "ERROR: could not open primes.txt for writing\n");
        return 1;
    }
    std::setvbuf(gOutFile, fileBuf, _IOFBF, sizeof(fileBuf));

    auto t3 = Clock::now();
    printf("Phase 2 -- open output file:                                    [%.3f ms]\n",
           nanosToMs(std::chrono::duration_cast<std::chrono::nanoseconds>(t3-t2).count()));

    /* ------------------------------------------------------------------
     * Phase 3: Segmented sieve.
     *
     * Segment layout:
     *   bit k in seg[] -> odd number  low + 2k
     *   cleared bit (0) -> prime
     *   set    bit (1) -> composite
     * ------------------------------------------------------------------ */
    auto t4 = Clock::now();

    long long primeCount = 1LL; // 2 counted here
    appendPrime(2LL, /*first=*/true);

    // Precompute nextMultiple[pi] = first odd composite multiple of smallPrimes[pi+1]
    // that falls at or after low=3.  Index pi maps to smallPrimes[pi+1] (skips p=2).
    int oddCount = static_cast<int>(smallPrimes.size()) - 1;
    std::vector<long long> nextMultiple(oddCount);
    {
        long long firstLow = 3LL;
        for (int pi = 0; pi < oddCount; pi++) {
            long long p = smallPrimes[pi + 1];
            long long s = p * p;
            if (s < firstLow) s = ((firstLow + p - 1) / p) * p;
            if ((s & 1LL) == 0LL) s += p;
            nextMultiple[pi] = s;
        }
    }

    // Ranges of the two prime classes (pi is offset by 1 for the odd-prime array)
    int medEnd   = splitIdx - 1;        // medium primes: pi in [0, medEnd)
    int largeEnd = oddCount;            // large  primes: pi in [medEnd, largeEnd)

    for (long long low = 3LL; low <= LIMIT; low += 2LL * SEG_BITS) {
        long long high      = std::min(low + 2LL * SEG_BITS - 2LL, static_cast<long long>(LIMIT));
        int       bitsInSeg = static_cast<int>((high - low) / 2) + 1;
        int       longsNeeded = (bitsInSeg + 63) / 64;

        // --- Zero segment (AVX2 or memset) ---
        zeroSeg(longsNeeded);

        // --- Mark composites: medium primes (many hits per segment) ---
        for (int pi = 0; pi < medEnd; pi++) {
            long long p   = smallPrimes[pi + 1];
            long long cur = nextMultiple[pi];
            long long step = 2LL * p;
            while (cur <= high) {
                int bitIdx = static_cast<int>((cur - low) >> 1);
                seg[bitIdx >> 6] |= UINT64_C(1) << (bitIdx & 63);
                cur += step;
            }
            nextMultiple[pi] = cur;
        }

        // --- Mark composites: large primes (0 or 1 hit per segment) ---
        for (int pi = medEnd; pi < largeEnd; pi++) {
            long long cur = nextMultiple[pi];
            if (cur <= high) {
                int bitIdx = static_cast<int>((cur - low) >> 1);
                seg[bitIdx >> 6] |= UINT64_C(1) << (bitIdx & 63);
                nextMultiple[pi] = cur + 2LL * smallPrimes[pi + 1];
            }
        }

        // --- Harvest primes (AVX2 + ctz word-at-a-time) ---
        harvestSeg(low, bitsInSeg, longsNeeded, primeCount);
    }

    flushWriteBuf();

    auto t5 = Clock::now();
    printf("Phase 3 -- segmented sieve + buffered write:                    [%.3f ms]\n",
           nanosToMs(std::chrono::duration_cast<std::chrono::nanoseconds>(t5-t4).count()));

    /* ------------------------------------------------------------------
     * Phase 4: Flush and close.
     * ------------------------------------------------------------------ */
    auto t6 = Clock::now();
    std::fclose(gOutFile);
    auto t7 = Clock::now();
    printf("Phase 4 -- flush + close:                                       [%.3f ms]\n",
           nanosToMs(std::chrono::duration_cast<std::chrono::nanoseconds>(t7-t6).count()));

    /* ------------------------------------------------------------------
     * Summary
     * ------------------------------------------------------------------ */
    auto totalEnd = Clock::now();
    double totalMs = nanosToMs(
        std::chrono::duration_cast<std::chrono::nanoseconds>(totalEnd - totalStart).count());

    printf("\n");
    printf("Total primes found : %s\n", fmtComma(primeCount).c_str());
    printf("Output file        : primes.txt\n");
    printf("Total elapsed      : %.3f ms  (%.3f s)\n", totalMs, totalMs / 1000.0);

    return 0;
}
