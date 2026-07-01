import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * PrimeFinder — writes the first 1,000,000,000 primes to "primes.txt".
 *
 * Strategy
 * ────────
 * 1. Simple Sieve of Eratosthenes for primes up to √(UPPER_BOUND) ≈ 151 658.
 * 2. Segmented sieve over the remaining range, odd numbers only (halves work
 *    and memory vs a naïve sieve).
 * 3. Each segment is processed by a thread from a fixed thread pool; segments
 *    are collected in submission order so output stays sorted without sorting.
 * 4. Numbers are ASCII-formatted in memory and bulk-flushed to a direct
 *    NIO ByteBuffer → FileChannel to minimise system-call overhead.
 *
 * The 1 000 000 000th prime is 22 801 763 489.  UPPER_BOUND is set just above.
 */
public class PrimeFinder {

    // ── Tunables ─────────────────────────────────────────────────────────────

    /** How many primes to find (overridable via first CLI arg). */
    static long TARGET = 1_000_000_000L;

    /** Safe ceiling; the 10^9-th prime is 22 801 763 489. */
    static final long UPPER_BOUND = 23_000_000_000L;

    /**
     * Number of *odd* integers covered per sieve segment.
     * 2^21 = 2 097 152 odds → 256 KB bit-array per segment (fits in L2/L3 cache).
     */
    static final int SEGMENT_ODDS = 1 << 21;

    /** Number of CPU cores to use. */
    static final int THREADS = Runtime.getRuntime().availableProcessors();

    /** Segments submitted ahead of the consumer (keeps all cores busy). */
    static final int PIPELINE_DEPTH = Math.max(THREADS * 2, 8);

    /** NIO output buffer: 64 MB. */
    static final int IO_BUF_BYTES = 1 << 26;

    // ── Shared small-prime table (read-only after init) ───────────────────────
    static int[] smallPrimes;
    static int   smallPrimeCount;

    // ── Scratch buffer for number→ASCII conversion (serial writes, no races) ──
    static final byte[] SCRATCH = new byte[22]; // 20 digits + '\n' + pad

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        // Optional CLI override: java PrimeFinder <count>
        if (args.length > 0) {
            try {
                TARGET = Long.parseLong(args[0].replace("_", "").replace(",", ""));
                System.out.printf("Target overridden via CLI: %,d primes%n", TARGET);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java PrimeFinder [count]  (default 1,000,000,000)");
                System.exit(1);
            }
        }

        long t0 = System.currentTimeMillis();

        // ── Step 1: build small-prime table via simple sieve ─────────────────
        int sqrtBound = (int) Math.ceil(Math.sqrt((double) UPPER_BOUND));
        boolean[] composite = new boolean[sqrtBound + 1];
        for (int i = 2; (long) i * i <= sqrtBound; i++) {
            if (!composite[i]) {
                for (int j = i * i; j <= sqrtBound; j += i) composite[j] = true;
            }
        }
        int sc = 0;
        for (int i = 2; i <= sqrtBound; i++) if (!composite[i]) sc++;
        smallPrimes      = new int[sc];
        smallPrimeCount  = 0;
        for (int i = 2; i <= sqrtBound; i++) {
            if (!composite[i]) smallPrimes[smallPrimeCount++] = i;
        }
        System.out.printf("[%5.1fs] Small-prime table: %,d primes up to %,d  (threads=%d)%n",
                elapsed(t0), smallPrimeCount, sqrtBound, THREADS);

        // ── Step 2: segmented sieve + write ──────────────────────────────────
        try (FileOutputStream fos  = new FileOutputStream("primes.txt");
             FileChannel      chan = fos.getChannel()) {

            ByteBuffer ioBuf = ByteBuffer.allocateDirect(IO_BUF_BYTES);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            Deque<Future<long[]>> pipeline = new ArrayDeque<>(PIPELINE_DEPTH);

            long totalWritten = 0;
            long nextLow      = 3L;      // next segment start (always odd)
            boolean submitted = false;   // true when all segments have been queued

            // The lone even prime
            writeNum(2L, ioBuf, chan);
            totalWritten = 1;

            outer:
            while (true) {

                // ── fill pipeline ────────────────────────────────────────────
                while (!submitted && pipeline.size() < PIPELINE_DEPTH) {
                    if (nextLow > UPPER_BOUND) { submitted = true; break; }
                    final long lo = nextLow;
                    final long hi = Math.min(nextLow + 2L * SEGMENT_ODDS - 2, UPPER_BOUND);
                    nextLow = hi + 2;
                    pipeline.addLast(pool.submit(() -> sieve(lo, hi)));
                }

                if (pipeline.isEmpty()) break; // all done

                // ── drain head (in order → sorted output) ────────────────────
                long[] primes = pipeline.pollFirst().get();

                long remaining = TARGET - totalWritten;
                long toWrite   = Math.min(primes.length, remaining);

                for (int k = 0; k < toWrite; k++) {
                    writeNum(primes[k], ioBuf, chan);
                }
                totalWritten += toWrite;

                if (totalWritten % 50_000_000 == 0) {
                    System.out.printf("[%5.1fs] %,d primes written, last = %,d%n",
                            elapsed(t0), totalWritten, primes[(int) toWrite - 1]);
                }

                if (totalWritten >= TARGET) {
                    // Cancel any still-running futures
                    for (Future<?> f : pipeline) f.cancel(false);
                    pipeline.clear();
                    break outer;
                }
            }

            // Flush remaining bytes
            flushBuf(ioBuf, chan);

            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            System.out.printf("%n[%5.1fs] Finished — %,d primes written to primes.txt%n",
                    elapsed(t0), totalWritten);
        }
    }

    // ── Segmented sieve ───────────────────────────────────────────────────────

    /**
     * Returns every prime in the closed interval [low, high].
     * Both low and high must be odd.
     */
    static long[] sieve(long low, long high) {
        // Index mapping: odd integer n  →  (n - low) / 2
        int size = (int) ((high - low) / 2 + 1);
        byte[] sieve = new byte[(size + 7) >>> 3]; // bit-packed, 0 = prime

        // Mark composites using each small odd prime
        for (int pi = 1; pi < smallPrimeCount; pi++) {   // pi=0 → p=2, skip
            long p = smallPrimes[pi];

            // Smallest odd multiple of p that is ≥ low (and ≠ p itself)
            long firstMult = ((low + p - 1) / p) * p;
            if ((firstMult & 1L) == 0L) firstMult += p;  // ensure odd
            if (firstMult == p)          firstMult = p * p; // p is prime, skip to p²

            if (firstMult > high) continue;

            // Index arithmetic: each step of 2p in value = p in index
            int idx  = (int) ((firstMult - low) >>> 1);
            int step = (int) p;
            int last = (size - 1);

            for (; idx <= last; idx += step) {
                sieve[idx >>> 3] |= (byte) (1 << (idx & 7));
            }
        }

        // Collect prime candidates
        int count = 0;
        for (int i = 0; i < size; i++) {
            if ((sieve[i >>> 3] & (1 << (i & 7))) == 0) count++;
        }
        long[] result = new long[count];
        int ri = 0;
        for (int i = 0; i < size; i++) {
            if ((sieve[i >>> 3] & (1 << (i & 7))) == 0) {
                result[ri++] = low + ((long) i << 1);
            }
        }
        return result;
    }

    // ── NIO write helpers ─────────────────────────────────────────────────────

    /** Write a long as ASCII decimal followed by '\n'. */
    static void writeNum(long n, ByteBuffer buf, FileChannel chan) throws IOException {
        // Convert digits right-to-left into SCRATCH, then '\n'
        int end = 20;
        SCRATCH[end] = '\n';
        int pos = end - 1;
        long rem = n;
        do {
            SCRATCH[pos--] = (byte) ('0' + rem % 10);
            rem /= 10;
        } while (rem != 0);
        // digits occupy SCRATCH[pos+1 .. end-1], newline at end
        int len = end - pos; // includes '\n'
        if (buf.remaining() < len) flushBuf(buf, chan);
        buf.put(SCRATCH, pos + 1, len);
    }

    static void flushBuf(ByteBuffer buf, FileChannel chan) throws IOException {
        buf.flip();
        while (buf.hasRemaining()) chan.write(buf);
        buf.clear();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    static double elapsed(long t0) {
        return (System.currentTimeMillis() - t0) / 1e3;
    }
}
