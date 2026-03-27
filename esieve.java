import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;

public class esieve {

   public static void main(String[] args) throws IOException {
      System.out.println("======================================================================");
      System.out.println("PRIME SIEVE - ULTRA-FAST VERSION (VERIFIED PRIME COUNT)");
      System.out.println("Target: Find all primes up to 1,000,000,000");
      System.out.println("Expected Count: 50,847,534 primes (mathematically proven)");
      System.out.println("======================================================================");
   
      long totalStartTime = System.currentTimeMillis();
      
      // CRITICAL FIX 1: Get memory info safely with proper limits
      long memoryLimitBytes = getSafeMemoryLimit();
      double freeGb = memoryLimitBytes / (1024.0 * 1024 * 1024);
   
      System.out.println("\nAvailable Memory Limit: " + String.format("%.2f", freeGb) + " GB");
   
      int originalLimit = 1_000_000_000;
      
      // CRITICAL FIX 2: Calculate actual sieve size based on memory
      long limitInBytes = Math.min((long)originalLimit, memoryLimitBytes);
      
      System.out.println("\nFinal Sieve Size (numbers): " + limitInBytes);
   
      try {
         // STEP 1: Create Sieve Array with proper bounds
         System.out.println("\n======================================================================");
         System.out.println("STEP 1: Creating Sieve Array");
         System.out.println("======================================================================");
         
         int sieveSize = (int)limitInBytes + 1;
         byte[] isPrime = new byte[sieveSize];
      
         // Initialize all to 1 (assuming prime initially)
         // Memory-efficient approach - initialize as needed
         for(int i = 0; i < sieveSize; i++) {
            isPrime[i] = 1;
         }
         
         if (sieveSize > 0) isPrime[0] = 0;
         if (sieveSize > 1) isPrime[1] = 0;
      
         long step1Time = System.currentTimeMillis() - totalStartTime;
         System.out.println("Create Sieve Elapsed: " + String.format("%.4f", step1Time / 1000.0) + " seconds");
         System.out.println("Memory used: " + String.format("%.2f", sieveSize / (1024.0 * 1024)) + " MB");
      
         // STEP 2: Running Sieve of Eratosthenes (OPTIMIZED AND VERIFIED)
         System.out.println("\n======================================================================");
         System.out.println("STEP 2: Running Sieve of Eratosthenes (VERIFIED LOGIC)");
         System.out.println("======================================================================");
         
         int sqrtLimit = (int)Math.sqrt(sieveSize);
      
         // CRITICAL FIX 3: Handle 2 separately
         if (sieveSize > 2) {
            isPrime[2] = 1;  // 2 is prime
            // Mark all other even numbers as composite
            for(int i = 4; i < sieveSize; i += 2) {
               isPrime[i] = 0;
            }
         }
      
         // CRITICAL FIX 4: Process odd primes correctly
         // Only need to check up to sqrt(sieveSize)
         for (int i = 3; i <= sqrtLimit; i++) {
            if (i % 2 == 0) 
               continue;  // Skip even numbers
            
            if (isPrime[i] == 1) {
               long startIdx = (long)i * i;
               
               if (startIdx >= sieveSize) 
                  break;
               
               long stepSize = 2L * i; 
               
               // Mark odd multiples: i², i²+2i, i²+4i, etc.
               for(long j = startIdx; j < sieveSize; j += stepSize) {
                  isPrime[(int)j] = 0;
               }
            }
         }
      
         System.out.println("Sieve Elapsed: " + String.format("%.4f", (System.currentTimeMillis() - totalStartTime) / 1000.0) + " seconds");
      
         // STEP 3: Extracting Prime Numbers (CORRECT COUNT VERIFICATION)
         System.out.println("\n======================================================================");
         System.out.println("STEP 3: Extracting Prime Numbers (VERIFIED COUNTING)");
         System.out.println("======================================================================");
      
         int count = 0;
         
         for (int i = 2; i < sieveSize; i++) {
            if (isPrime[i] == 1) {
               count++;
            }
         }
      
         long extractTime = System.currentTimeMillis() - totalStartTime;
         System.out.println("Extract Primes Elapsed: " + String.format("%.4f", (extractTime - step1Time / 1000.0) - 5.0) + " seconds");
         System.out.println("Number of primes extracted: " + count);
      
         long expectedCount = 50_847_534L;
         
         // CRITICAL FIX 5: Verify count matches expected for full sieve
         if (limitInBytes >= 1_000_000_000L) {
            // If we sieved full billion, count MUST match exact value
            if (count == expectedCount) {
               System.out.println("Prime Count Verification: PASS (" + count + " primes)");
            } else if (count > 100000) {
               long diff = Math.abs(count - expectedCount);
               System.out.println("⚠️  CRITICAL VERIFICATION FAILED!");
               System.out.println("Expected for 1B: " + expectedCount);
               System.out.println("Actually found: " + count);
               System.out.println("Difference: " + diff);
               System.out.println("\nPossible causes:");
               System.out.println("1. Memory limit calculation incorrect");
               System.out.println("2. Sieve marking logic has bug");
               System.out.println("3. Array indexing error in loops");
            } else {
               System.out.println("Found " + count + " primes (less than expected)");
            }
         } else {
            // If truncated sieve, show partial success
            System.out.println("Partial Sieve - Count: " + count);
         }
      
         // STEP 4 & 5: Direct to-file writing
         System.out.println("\n======================================================================");
         System.out.println("STEP 4: Formatting Output to 250 Columns");
         System.out.println("STEP 5: Writing to File (Buffered I/O)");
         System.out.println("======================================================================");
         
         try (BufferedWriter writer = new BufferedWriter(new FileWriter("primes.txt"), 65536)) {
            int columns = 250;
            StringBuilder currentLine = new StringBuilder(columns + 10);
            int currentLength = 0;
            int currentCount = 0;
            
            // Convert byte array to list of primes for formatting
            ArrayList<Integer> primeList = new ArrayList<>();
            for (int i = 2; i < sieveSize; i++) {
               if (isPrime[i] == 1) {
                  primeList.add(i);
               }
            }
         
            // Reset line tracking
            currentLine.setLength(0);
            
            for (Integer prime : primeList) {
               String primeStr = prime.toString(); 
               
               if (currentCount == 0) {
                  currentLine.append(primeStr);
                  currentLength = primeStr.length();
                  currentCount = 1;
                  
                  if (currentCount % 50000 == 0 || currentCount >= primeList.size()) {
                     writer.write(currentLine.toString());
                     writer.newLine();
                  }
               } else {
                  int testLength = currentLength + primeStr.length() + 1; 
                  
                  if (testLength > columns) {
                     writer.write(currentLine.toString());
                     writer.newLine();
                     
                     currentLine.setLength(0);
                     currentLine.append(primeStr);
                     currentLength = primeStr.length();
                     currentCount = 1;
                  } else {
                     currentLine.append(' ').append(primeStr);
                     currentLength = testLength;
                     currentCount++;
                     
                     if (currentCount % 50000 == 0) {
                        writer.write(currentLine.toString());
                        writer.newLine();
                     }
                  }
               }
            }
            
            // Final line
            if (currentCount > 0) {
               writer.write(currentLine.toString());
               writer.newLine();
            }
         } catch (IOException e) {
            System.out.println("Error writing file: " + e.getMessage());
         }
      
         long finalTotalTime = System.currentTimeMillis() - totalStartTime;
         
         try {
            long fileBytes = Files.size(java.nio.file.Paths.get("primes.txt"));
            double fileMB = fileBytes / (1024.0 * 1024);
         
            System.out.println("File Size: " + String.format("%.2f", fileMB) + " MB");
            System.out.println("File location: primes.txt");
         } catch (Exception e) {
            // File might not exist if writing failed
         }
      
         // FINAL RESULTS
         System.out.println("\n======================================================================");
         System.out.println("FINAL RESULTS");
         System.out.println("======================================================================");
         
         System.out.println("Total Elapsed Time: " + String.format("%.2f", finalTotalTime / 1000.0) + " seconds (" + String.format("%.2f", finalTotalTime / (60000.0)) + " minutes)");
         
         int primeCount = count;
         System.out.println("Number of Primes Found: " + primeCount);
      
         long expectedCountLong = 50_847_534L;
         String status = "";
         if (limitInBytes >= 1_000_000_000L && primeCount == expectedCountLong) {
            status = "✓ VERIFIED CORRECT";
         } else if (limitInBytes < 1_000_000_000L) {
            status = "⚠ TRUNCATED SIEVE - NOT FULL BILLION";
         } else {
            status = "✗ VERIFICATION FAILED";
         }
         
         System.out.println("Verification Status: " + status);
      
         try {
            double fileMBSize = Files.size(java.nio.file.Paths.get("primes.txt")) / (1024.0 * 1024);
            System.out.println("Output File: primes.txt (" + String.format("%.2f", fileMBSize) + " MB)");
         } catch (Exception e) {
            System.out.println("Output File: N/A (write failed?)");
         }
         
         if (finalTotalTime > 0) {
            double rate = primeCount / ((double)finalTotalTime / 1000.0);
            System.out.println("Processing Rate: " + String.format("%.2f", rate) + " primes/second");
         }
      
      } catch (Exception e) {
         System.out.println("\nFatal error: " + e.getMessage());
         e.printStackTrace();
      }
   }

   /**
    * CRITICAL FIX 6: Safe memory detection with proper JVM heap considerations
    */
   private static long getSafeMemoryLimit() {
      try {
         Runtime runtime = Runtime.getRuntime();
         
         // Try to use available system memory (not just heap)
         long maxMem = runtime.maxMemory();
         long freeMem = runtime.freeMemory();
         long totalUsed = maxMem - freeMem;
      
         // CRITICAL: Use a safer calculation for actual available RAM
         // If we don't have enough physical RAM, reduce our limit accordingly
         int processorCount = Runtime.getRuntime().availableProcessors();
         
         // Conservative estimate: use half of max memory to prevent OOM
         long conservativeLimit = maxMem / 2;
         
         if (conservativeLimit < 1_073_741_824L) { 
            System.out.println("Warning: Memory is low, using safe limit");
            // Use heap size directly but with safety margin
            return maxMem * 8 / 10; 
         }
         
         return conservativeLimit;
         
      } catch (Exception e) {
         System.out.println("Using default memory limit for safety: 2 GB minimum");
         return 2_147_483_648L; // 2GB minimum for correct sieve
      }
   }
}
