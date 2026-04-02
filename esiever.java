import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

public class esiever {

   public static void main(String[] args) throws IOException {
      long totalStartTime = System.currentTimeMillis();
      long requestedLimit = 1_000_000_000L;
      
      System.out.println("======================================================================");
      System.out.println("PRIME SIEVE - ULTRA-FULLY OPTIMIZED (FIXED COMPILATION)");
      System.out.println("Target: " + requestedLimit);
      System.out.println("Expected Count: 50,847,534 primes");
      System.out.println("======================================================================");
   
      // STEP 1: Create Sieve Array
      System.out.println("\nSTEP 1: Creating Sieve Array");
      int sieveSize = (int) requestedLimit;
      
      // FIX: Use byte[] but cast values properly to avoid compilation error
      byte[] isPrime = new byte[sieveSize];
      
      for (int i = 2; i < sieveSize; i++) {
         // Added explicit cast to byte - this fixes the compilation error
         isPrime[i] = ((i & 1) == 0) ? (byte) 0 : (byte) 1;
      }
      if (sieveSize > 0) isPrime[0] = (byte) 0;
      if (sieveSize > 1) isPrime[1] = (byte) 0;
   
      System.out.println("Array Created: " + String.format("%.3f", 
         (System.currentTimeMillis() - totalStartTime) / 1000.0) + " seconds");
   
      // STEP 2: Sieve of Eratosthenes
      System.out.println("\nSTEP 2: Running Sieve of Eratosthenes");
      int sqrtLimit = (int) Math.sqrt(sieveSize);
      
      if (sieveSize > 2) {
         isPrime[2] = (byte) 1; // Explicit cast for 2
         for (int i = 4; i < sieveSize; i += 2) {
            isPrime[i] = (byte) 0; // Mark evens as composite
         }
      }
   
      long startSieveTime = System.currentTimeMillis();
      
      for (int i = 3; i <= sqrtLimit; i++) {
         if ((i & 1) != 0 && isPrime[i] == (byte) 1) { // Bitwise check with byte cast
            long startIdx = (long) i * i;
            
            if (startIdx >= sieveSize) 
               break;
         
            int stepSize = i << 1; // Fast bitwise: i * 2
            
            for (int j = (int) startIdx; j < sieveSize; j += stepSize) {
               isPrime[j] = (byte) 0; // Mark multiples as composite with cast
            }
         }
      }
   
      long step2Time = System.currentTimeMillis() - startSieveTime;
      System.out.println("Sieve Completed: " + String.format("%.3f", 
         (step2Time / 1000.0)) + " seconds");
   
      // STEP 3: OPTIMIZED SINGLE-PASS COUNTING & STORAGE
      System.out.println("\nSTEP 3: Counting Primes (OPTIMIZED SINGLE-PASS)");
      
      int count = 0;
      
      // First pass: Count primes to know exact size needed
      for (int i = 2; i < sieveSize; i++) {
         if (isPrime[i] == (byte) 1) { // Byte comparison
            count++;
         }
      }
   
      System.out.println("First Pass Complete - Count: " + count);
   
      // Second pass: Copy into pre-sized array (still fast!)
      int[] primes = new int[count];
      
      int index = 0;
      for (int i = 2; i < sieveSize; i++) {
         if (isPrime[i] == (byte) 1) { // Byte comparison
            primes[index++] = i;
         }
      }
   
      System.out.println("Second Pass Complete - Primes Stored");
      
      // Write in larger chunks
      System.out.println("\nSTEP 4: Writing to File (OPTIMIZED DIRECT ARRAY ACCESS)");
      System.out.println("======================================================================");
   
      try (BufferedWriter writer = new BufferedWriter(
         new FileWriter("primes.txt"), 131072)) { // Larger buffer: 128KB
         int columns = 100;
         StringBuilder currentLine = new StringBuilder(4096);
         int currentLength = 0;
         int lineCount = 0;
      
         for (int prime : primes) {
            String primeStr = Integer.toString(prime);
         
            if (currentLength == 0) {
               currentLine.append(primeStr);
               currentLength = primeStr.length();
               
               // Flush every full line or at end
               if (lineCount % 500_000 == 0 || lineCount >= primes.length - 1) {
                  writer.write(currentLine.toString());
                  writer.newLine();
                  lineCount++;
               }
            } else {
               int testLength = currentLength + primeStr.length() + 1;
            
               if (testLength > columns) {
                  // Full column reached - flush and start new line
                  writer.write(currentLine.toString());
                  writer.newLine();
                  
                  currentLine.setLength(0);
                  currentLine.append(primeStr);
                  currentLength = primeStr.length();
                  lineCount++;
               } else {
                  currentLine.append(' ').append(primeStr);
                  currentLength = testLength;
               
                  // Flush less frequently for better throughput
                  if (lineCount % 500_000 == 0) {
                     writer.write(currentLine.toString());
                     writer.newLine();
                     lineCount++;
                  }
               }
            }
         }
      
         // Write final line if not empty
         if (currentLength > 0) {
            writer.write(currentLine.toString());
            writer.newLine();
         }
      } catch (IOException e) {
         System.out.println("Error writing file: " + e.getMessage());
      }
   
      long finalTotalTime = System.currentTimeMillis() - totalStartTime;
   
      // STEP 5: Final Statistics
      System.out.println("\n======================================================================");
      System.out.println("FINAL RESULTS");
      System.out.println("======================================================================");
   
      long expectedCountFor1B = 50_847_534L;
      System.out.println("Target Limit: " + requestedLimit);
      System.out.println("Total Elapsed Time: " + String.format("%.2f", 
         finalTotalTime / 1000.0) + " seconds (" + 
         String.format("%.2f", finalTotalTime / 60000.0) + " minutes)");
      
      System.out.println("Number of Primes Found: " + count);
   
      if (count == expectedCountFor1B) {
         System.out.println("Verification Status: ✓ VERIFIED CORRECT");
      } else {
         System.out.println("Verification Status: ⚠ COUNT MISMATCH");
         System.out.println("Expected: " + expectedCountFor1B);
         System.out.println("Found: " + count);
      }
   
      try {
         double fileMB = Files.size(java.nio.file.Paths.get("primes.txt")) / 
            (1024.0 * 1024);
         
         System.out.println("Output File Size: " + String.format("%.2f", fileMB) + " MB");
         
         if (finalTotalTime > 0) {
            double rate = count / ((double) finalTotalTime / 1000.0);
            System.out.println("Processing Rate: " + String.format("%.2f", 
               rate) + " primes/second");
         }
      
      } catch (Exception e) {
         System.out.println("File Statistics: N/A");
      }
   
   }
}
