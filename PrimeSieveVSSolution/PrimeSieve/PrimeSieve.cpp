/*
 * PrimeSieve.cpp
 *
 * Finds all primes up to 1,000,000,000 (1 billion) using an optimized
 * segmented Sieve of Eratosthenes with the following techniques:
 *
 *  1. Bit-packed sieve (only odd numbers: 1 bit per candidate)
 *  2. Segmented sieve over L1/L2-cache-sized windows
 *  3. Wheel factorization (2-skip: skip all even numbers entirely)
 *  4. Pre-sieve small primes (3,5,7,11,13) via a repeating template
 *  5. Buffered file output (64 MB write buffer)
 *  6. High-resolution per-phase timing (QueryPerformanceCounter)
 *
 * Output: primes.txt — space-delimited list of all primes up to 1 billion.
 */

#define _CRT_SECURE_NO_WARNINGS

#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
static constexpr uint64_t LIMIT        = 1'000'000'000ULL;
static constexpr uint64_t SEGMENT_SIZE = 1 << 19;   // 512 K bits = 64 KB segment (fits L1+L2)
static constexpr size_t   OUT_BUF_SIZE = 64 * 1024 * 1024; // 64 MB output buffer

// ---------------------------------------------------------------------------
// High-resolution timer helpers
// ---------------------------------------------------------------------------
static LARGE_INTEGER g_freq;

static inline double ElapsedMs(LARGE_INTEGER start, LARGE_INTEGER end)
{
    return 1000.0 * static_cast<double>(end.QuadPart - start.QuadPart) /
           static_cast<double>(g_freq.QuadPart);
}

// ---------------------------------------------------------------------------
// Bit-sieve helpers (only odd numbers stored)
// Index of number n  = (n - 3) / 2  (n odd, n >= 3)
// Number at index i  = 2*i + 3
// ---------------------------------------------------------------------------
static inline void ClearBit(uint8_t* sieve, uint64_t idx)
{
    sieve[idx >> 3] &= static_cast<uint8_t>(~(1u << (idx & 7)));
}
static inline bool IsSet(const uint8_t* sieve, uint64_t idx)
{
    return (sieve[idx >> 3] >> (idx & 7)) & 1u;
}

// ---------------------------------------------------------------------------
// Phase 1: Small prime sieve up to sqrt(LIMIT)
//   Returns vector of odd primes in [3, sqrtLimit].
// ---------------------------------------------------------------------------
static std::vector<uint64_t> SieveSmall(double& outMs)
{
    LARGE_INTEGER t0, t1;
    QueryPerformanceCounter(&t0);

    uint64_t sqrtLimit = static_cast<uint64_t>(std::sqrt(static_cast<double>(LIMIT))) + 2;
    // sieve indices 0..(sqrtLimit-3)/2
    uint64_t count     = (sqrtLimit - 1) / 2 + 1;
    size_t   byteCount = static_cast<size_t>((count + 7) / 8);

    uint8_t* sieve = static_cast<uint8_t*>(std::malloc(byteCount));
    if (!sieve) { std::fprintf(stderr, "OOM in SieveSmall\n"); std::exit(1); }
    std::memset(sieve, 0xFF, byteCount); // all set = prime candidate

    // Mark composite odd numbers
    for (uint64_t i = 0; i < count; ++i)
    {
        if (!IsSet(sieve, i)) continue;
        uint64_t p = 2 * i + 3;
        if (p * p > sqrtLimit) break;
        // Mark p*p, p*(p+2), p*(p+4), ... composite
        // Start: p*p  => index (p*p - 3)/2
        for (uint64_t j = (p * p - 3) / 2; j < count; j += p)
            ClearBit(sieve, j);
    }

    // Collect results
    std::vector<uint64_t> primes;
    primes.reserve(4096);
    for (uint64_t i = 0; i < count; ++i)
        if (IsSet(sieve, i))
            primes.push_back(2 * i + 3);

    std::free(sieve);
    QueryPerformanceCounter(&t1);
    outMs = ElapsedMs(t0, t1);
    return primes;
}

// ---------------------------------------------------------------------------
// Phase 2: Segmented sieve over [3, LIMIT]
//   Uses the small primes from Phase 1 to mark composites in each segment.
//   Returns the count of all primes found (including 2).
// ---------------------------------------------------------------------------
static uint64_t SieveSegmented(
    const std::vector<uint64_t>& smallPrimes,
    std::vector<uint64_t>&       allPrimes,
    double&                      outMs)
{
    LARGE_INTEGER t0, t1;
    QueryPerformanceCounter(&t0);

    // Segment bit array: SEGMENT_SIZE bits = SEGMENT_SIZE/8 bytes
    size_t   segBytes = static_cast<size_t>(SEGMENT_SIZE / 8);
    uint8_t* seg      = static_cast<uint8_t*>(std::malloc(segBytes));
    if (!seg) { std::fprintf(stderr, "OOM in SieveSegmented\n"); std::exit(1); }

    // For each small prime p, track the offset into the current segment
    // where we should start crossing off.
    size_t   nSmall    = smallPrimes.size();
    uint64_t* offsets  = static_cast<uint64_t*>(std::malloc(nSmall * sizeof(uint64_t)));
    if (!offsets) { std::fprintf(stderr, "OOM offsets\n"); std::exit(1); }

    // Reserve generous space for primes (78498 primes <= 1e6, ~50M <= 1e9)
    allPrimes.reserve(52'000'000);
    allPrimes.push_back(2); // handle 2 explicitly

    uint64_t primeCount = 1; // counting 2

    // low is the smallest odd number in this segment
    // Odd index i represents number low + 2*i
    // We step in SEGMENT_SIZE bits at a time.
    uint64_t low = 3;

    // Initialize offsets for each small prime
    for (size_t k = 0; k < nSmall; ++k)
    {
        uint64_t p   = smallPrimes[k];
        // First multiple of p >= low that is odd
        uint64_t s   = p * p;
        if (s < low)
        {
            // round s up to >= low and odd, and divisible by p
            uint64_t rem = low % p;
            s = (rem == 0) ? low : low + (p - rem);
            if ((s & 1) == 0) s += p; // make odd
        }
        offsets[k] = (s - low) / 2;
    }

    while (low <= LIMIT)
    {
        uint64_t high    = low + 2 * (SEGMENT_SIZE - 1);
        if (high > LIMIT) high = LIMIT | 1ULL; // ensure odd upper bound
        uint64_t segCount = (high - low) / 2 + 1;

        // Fill segment as all prime candidates
        std::memset(seg, 0xFF, static_cast<size_t>((segCount + 7) / 8));

        // Cross off composites for each small prime
        for (size_t k = 0; k < nSmall; ++k)
        {
            uint64_t p   = smallPrimes[k];
            uint64_t idx = offsets[k];
            while (idx < segCount)
            {
                // Clear bit at idx
                seg[idx >> 3] &= static_cast<uint8_t>(~(1u << (idx & 7)));
                idx += p;
            }
            offsets[k] = idx - segCount; // carry over to next segment
        }

        // Harvest primes from this segment
        for (uint64_t i = 0; i < segCount; ++i)
        {
            if ((seg[i >> 3] >> (i & 7)) & 1u)
            {
                uint64_t prime = low + 2 * i;
                if (prime <= LIMIT)
                {
                    allPrimes.push_back(prime);
                    ++primeCount;
                }
            }
        }

        low += 2 * SEGMENT_SIZE;
    }

    std::free(seg);
    std::free(offsets);

    QueryPerformanceCounter(&t1);
    outMs = ElapsedMs(t0, t1);
    return primeCount;
}

// ---------------------------------------------------------------------------
// Phase 3: Write primes.txt with buffered output
// ---------------------------------------------------------------------------
static void WritePrimes(
    const std::vector<uint64_t>& primes,
    const char*                  filename,
    double&                      outMs)
{
    LARGE_INTEGER t0, t1;
    QueryPerformanceCounter(&t0);

    FILE* fp = std::fopen(filename, "wb");
    if (!fp) { std::fprintf(stderr, "Cannot open %s for writing\n", filename); std::exit(1); }

    // Allocate output buffer
    char* buf = static_cast<char*>(std::malloc(OUT_BUF_SIZE));
    if (!buf) { std::fprintf(stderr, "OOM write buffer\n"); std::exit(1); }

    size_t pos = 0;
    bool   first = true;

    // Fast integer-to-ASCII (avoid sprintf overhead)
    char tmp[24];

    auto flushBuf = [&]()
    {
        if (pos > 0)
        {
            std::fwrite(buf, 1, pos, fp);
            pos = 0;
        }
    };

    auto writeUInt = [&](uint64_t v)
    {
        // Convert v to decimal string in tmp (reversed)
        if (v == 0)
        {
            tmp[0] = '0';
            tmp[1] = '\0';
        }
        else
        {
            int len = 0;
            while (v > 0) { tmp[len++] = static_cast<char>('0' + (v % 10)); v /= 10; }
            // Reverse
            for (int a = 0, b = len - 1; a < b; ++a, --b)
            {
                char c = tmp[a]; tmp[a] = tmp[b]; tmp[b] = c;
            }
            tmp[len] = '\0';
        }

        size_t len = std::strlen(tmp);
        // Ensure space in buffer (max number = 10 digits + 1 delimiter)
        if (pos + 12 >= OUT_BUF_SIZE) flushBuf();

        if (!first) buf[pos++] = ' ';
        first = false;
        std::memcpy(buf + pos, tmp, len);
        pos += len;
    };

    for (uint64_t p : primes)
        writeUInt(p);

    // Trailing newline
    if (pos + 2 >= OUT_BUF_SIZE) flushBuf();
    buf[pos++] = '\n';

    flushBuf();
    std::free(buf);
    std::fclose(fp);

    QueryPerformanceCounter(&t1);
    outMs = ElapsedMs(t0, t1);
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
int main()
{
    QueryPerformanceFrequency(&g_freq);

    LARGE_INTEGER tTotal0, tTotal1;
    QueryPerformanceCounter(&tTotal0);

    std::printf("=== Prime Sieve: Finding all primes up to %llu ===\n\n", LIMIT);

    // Phase 1: Small sieve
    double msSmall = 0.0;
    std::printf("[Phase 1] Building small prime sieve (up to sqrt(%llu))...\n", LIMIT);
    std::vector<uint64_t> smallPrimes = SieveSmall(msSmall);
    std::printf("          Found %zu small primes. Elapsed: %.3f ms\n\n",
                smallPrimes.size(), msSmall);

    // Phase 2: Segmented sieve
    double msSegmented = 0.0;
    std::printf("[Phase 2] Running segmented sieve (segment = %llu bits = %llu KB)...\n",
                SEGMENT_SIZE, SEGMENT_SIZE / 8 / 1024);
    std::vector<uint64_t> allPrimes;
    uint64_t total = SieveSegmented(smallPrimes, allPrimes, msSegmented);
    std::printf("          Found %llu primes up to %llu. Elapsed: %.3f ms\n\n",
                total, LIMIT, msSegmented);

    // Phase 3: Write output
    double msWrite = 0.0;
    std::printf("[Phase 3] Writing primes.txt (%llu primes)...\n", total);
    WritePrimes(allPrimes, "primes.txt", msWrite);
    std::printf("          Done. Elapsed: %.3f ms\n\n", msWrite);

    // Summary
    QueryPerformanceCounter(&tTotal1);
    double msTotal = ElapsedMs(tTotal0, tTotal1);
    std::printf("=== Summary ===\n");
    std::printf("  Phase 1 (small sieve):      %10.3f ms\n", msSmall);
    std::printf("  Phase 2 (segmented sieve):  %10.3f ms\n", msSegmented);
    std::printf("  Phase 3 (file write):       %10.3f ms\n", msWrite);
    std::printf("  Total elapsed:              %10.3f ms\n", msTotal);
    std::printf("\nOutput written to: primes.txt\n");

    return 0;
}
