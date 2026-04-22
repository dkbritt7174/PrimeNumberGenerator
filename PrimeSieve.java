import java.io.*;
import java.util.*;

/**
 * High-performance Sieve of Eratosthenes — finds all primes up to 1 billion.
 *
 * Techniques used:
 *   1. Odd-only storage: only odd numbers are tracked (2 is handled separately)
 *      => halves memory compared to tracking every integer
 *   2. Bit-packed segment array (long[]): 64 bits per long instead of 1 bit per boolean
 *      => another 64x reduction in segment memory usage
 *   3. Segmented sieve: processes one cache-friendly segment at a time
 *      => keeps hot data in L1/L2 cache (segment = 256 KB of bits = 4 million odd numbers)
 *   4. Pre-sieved small primes: compute primes up to sqrt(1e9) once with a plain sieve
 *   5. Large buffered output writer (8 MB buffer) for fast disk I/O
 *   6. Per-phase and total elapsed timing reported to stdout
 */
public class PrimeSieve {

   static final int LIMIT = 1_000_000_000;

   // Segment covers this many odd numbers at a time.
   // 256 KB of bits = 256 * 1024 * 8 = 2,097,152 bits = 2,097,152 odd numbers
   // Those odd numbers span a range of 2 * 2,097,152 = ~4.2 million integers — fits in L2 cache.
   static final int SEG_SIZE = 256 * 1024 * 8; // number of odd numbers per segment

   public static void main(String[] args) throws IOException {
   
      long totalStart = System.nanoTime();
      System.out.println("=== Sieve of Eratosthenes — primes to " +
             String.format("%,d", LIMIT) + " ===\n");
   
      /* ----------------------------------------------------------------
       * Phase 1: Plain sieve to find all primes up to sqrt(LIMIT).
       * sqrt(1e9) ≈ 31,623 — a tiny range, very fast.
       * These become the "marking primes" for the segmented sieve.
       * --------------------------------------------------------------- */
      long t0 = System.nanoTime();
   
      int sqrtLimit = (int) Math.ceil(Math.sqrt((double) LIMIT));
      boolean[] smallComp = new boolean[sqrtLimit + 1]; // composite flags; false = prime
      // 0 and 1 are not prime
      smallComp[0] = true;
      smallComp[1] = true;
      for (int i = 2; (long) i * i <= sqrtLimit; i++) {
         if (!smallComp[i]) {
            for (int j = i * i; j <= sqrtLimit; j += i) {
               smallComp[j] = true;
            }
         }
      }
      // Collect into an int array for fast iteration
      int smallPrimeCount = 0;
      for (int i = 2; i <= sqrtLimit; i++) {
         if (!smallComp[i]) smallPrimeCount++;
      }
      int[] smallPrimes = new int[smallPrimeCount];
      int idx = 0;
      for (int i = 2; i <= sqrtLimit; i++) {
         if (!smallComp[i]) smallPrimes[idx++] = i;
      }
   
      long t1 = System.nanoTime();
      System.out.printf("Phase 1 — small sieve up to %,d:     found %,d small primes   [%.3f ms]%n",
             sqrtLimit, smallPrimes.length, (t1 - t0) / 1_000_000.0);
   
      /* ----------------------------------------------------------------
       * Phase 2: Open output file with an 8 MB write buffer.
       * --------------------------------------------------------------- */
      long t2 = System.nanoTime();
   
      File outFile = new File("primes.txt");
      // 8 MB buffer significantly reduces syscall overhead
      BufferedWriter writer = new BufferedWriter(
             new OutputStreamWriter(
                     new FileOutputStream(outFile)),
             8 * 1024 * 1024);
   
      long t3 = System.nanoTime();
      System.out.printf("Phase 2 — open output file:                                    [%.3f ms]%n",
             (t3 - t2) / 1_000_000.0);
   
      /* ----------------------------------------------------------------
       * Phase 3: Segmented sieve over all odd numbers from 3 to LIMIT.
       *
       * Segment layout:
       *   bit 0 in segment -> the odd number `low`
       *   bit k in segment -> the odd number `low + 2*k`
       *
       * We bit-pack into long[] (64 bits each).
       * A cleared bit means the number is prime (not marked composite).
       * --------------------------------------------------------------- */
      long t4 = System.nanoTime();
   
      long primeCount = 1L; // count 2 separately
      // Use a StringBuilder for batching writes; reset every ~2 MB of content
      StringBuilder sb = new StringBuilder(2 * 1024 * 1024);
   
      // Write 2 first
      sb.append('2');
   
      // Bit-packed segment array: SEG_SIZE bits requires SEG_SIZE/64 longs
      long[] seg = new long[(SEG_SIZE + 63) / 64];
   
      // For each odd small prime p, track the next odd composite multiple so
      // we carry it forward across segments — no repeated division needed.
      // Skip index 0 (p=2) since we only sieve odd numbers.
      int oddPrimeCount = smallPrimes.length - 1; // exclude p=2
      long[] nextMultiple = new long[oddPrimeCount];
   
      long firstLow = 3L;
      for (int pi = 0; pi < oddPrimeCount; pi++) {
         long p = smallPrimes[pi + 1]; // skip p=2
         // Start marking from p*p (smallest composite not already crossed off),
         // or from the first odd multiple of p >= firstLow if p*p < firstLow.
         long startVal = p * p;
         if (startVal < firstLow) {
            startVal = ((firstLow + p - 1) / p) * p;
         }
         // Ensure startVal is odd
         if ((startVal & 1L) == 0L) startVal += p;
         nextMultiple[pi] = startVal;
      }
   
      for (long low = firstLow; low <= LIMIT; low += 2L * SEG_SIZE) {
         long high = Math.min(low + 2L * SEG_SIZE - 2L, LIMIT);
         int bitsInSeg = (int)((high - low) / 2) + 1; // odd positions in this segment
      
         // Clear only the longs we'll use this segment
         int longsNeeded = (bitsInSeg + 63) / 64;
         Arrays.fill(seg, 0, longsNeeded, 0L);
      
         // Mark odd composites: for each odd prime p, stride by 2p (odd multiples only)
         for (int pi = 0; pi < oddPrimeCount; pi++) {
            long p   = smallPrimes[pi + 1];
            long cur = nextMultiple[pi];
         
            while (cur <= high) {
               int bitIdx = (int)((cur - low) >> 1);
               seg[bitIdx >>> 6] |= (1L << (bitIdx & 63));
               cur += 2L * p;
            }
            nextMultiple[pi] = cur; // carry forward to next segment
         }
      
         // Harvest: any bit still 0 is prime
         long n = low;
         for (int bitIdx = 0; bitIdx < bitsInSeg; bitIdx++) {
            if ((seg[bitIdx >>> 6] & (1L << (bitIdx & 63))) == 0L) {
               sb.append(' ').append(n);
               primeCount++;
               // Flush StringBuilder to writer every ~2 MB to cap memory
               if (sb.length() >= 2 * 1024 * 1024) {
                  writer.write(sb.toString());
                  sb.setLength(0);
               }
            }
            n += 2L;
         }
      }
   
      // Flush any remaining primes in the buffer
      if (sb.length() > 0) {
         writer.write(sb.toString());
      }
   
      long t5 = System.nanoTime();
      System.out.printf("Phase 3 — segmented sieve + buffered write:                    [%.3f ms]%n",
             (t5 - t4) / 1_000_000.0);
   
      /* ----------------------------------------------------------------
       * Phase 4: Flush and close the file.
       * --------------------------------------------------------------- */
      long t6 = System.nanoTime();
      writer.close();
      long t7 = System.nanoTime();
      System.out.printf("Phase 4 — flush + close:                                       [%.3f ms]%n",
             (t7 - t6) / 1_000_000.0);
   
      /* ----------------------------------------------------------------
       * Summary
       * --------------------------------------------------------------- */
      long totalEnd = System.nanoTime();
      double fileMB = outFile.length() / (1024.0 * 1024.0);
      System.out.println();
      System.out.printf("Total primes found : %,d%n", primeCount);
      System.out.printf("Output file        : %s  (%.1f MB)%n", outFile.getAbsolutePath(), fileMB);
      System.out.printf("Total elapsed      : %.3f ms  (%.3f s)%n",
             (totalEnd - totalStart) / 1_000_000.0,
             (totalEnd - totalStart) / 1_000_000_000.0);
   }
}