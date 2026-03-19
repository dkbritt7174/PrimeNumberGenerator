import java.util.*;
import java.util.concurrent.*;
import java.io.*;

public class GfG {

   // Function to generate primes using Sieve of Atkin
   static ArrayList<Integer> sieveOfAtkin(int limit) {
      long startTime = System.nanoTime();
      
      // initialize the arr array with initial false values
      boolean[] arr = new boolean[limit + 1];
      Arrays.fill(arr, false);
      
      // mark 2 and 3 as prime
      if (limit > 2) arr[2] = true;
      if (limit > 3) arr[3] = true;
      
      // check for all three conditions
      for (int x = 1; x * x <= limit; x++) {
         for (int y = 1; y * y <= limit; y++) {
            
            // condition 1
            int n = (4 * x * x) + (y * y);
            if (n <= limit && (n % 12 == 1 || n % 12 == 5))
               arr[n] = !arr[n];
            
            // condition 2
            n = (3 * x * x) + (y * y);
            if (n <= limit && n % 12 == 7)
               arr[n] = !arr[n];
            
            // condition 3
            n = (3 * x * x) - (y * y);
            if (x > y && n <= limit && n % 12 == 11)
               arr[n] = !arr[n];
         }
      }
      
      // Mark all multiples of squares as non-prime
      for (int i = 5; i * i <= limit; i++) {
         if (!arr[i]) 
            continue;
         for (int j = i * i; j <= limit; j += i * i)
            arr[j] = false;
      }
      
      // store all prime numbers
      ArrayList<Integer> primes = new ArrayList<>();
      for (int i = 2; i <= limit; i++) {
         if (arr[i]) {
            primes.add(i);
         }
      }
      
      long endTime = System.nanoTime();
      float elapsedTime = (float)(endTime - startTime) / 1_000_000_000.0f;
      System.out.println("Sieve of Atkin execution time: " + elapsedTime + " seconds");
      
      return primes;
   }
   
   // Optimized version using parallel processing for the main sieve operations
   static ArrayList<Integer> sieveOfAtkinParallel(int limit) {
      long startTime = System.nanoTime();
      
      // initialize the arr array with initial false values
      boolean[] arr = new boolean[limit + 1];
      Arrays.fill(arr, false);
      
      // mark 2 and 3 as prime
      if (limit > 2) arr[2] = true;
      if (limit > 3) arr[3] = true;
      
      int processors = Runtime.getRuntime().availableProcessors();
      System.out.println("Finding all primes up to 1 billion");
      System.out.println("Using " + processors + " processors for parallel computation");
      
      // Use parallel processing for the main loop operations
      ExecutorService executor = Executors.newFixedThreadPool(processors);
      List<Future<Void>> futures = new ArrayList<>();
      
      // Process the first condition in parallel
      final int[] xLimit = {1};
      while (xLimit[0] * xLimit[0] <= limit) {
         final int currentX = xLimit[0];
         Future<Void> future = executor.submit(
            () -> {
               for (int y = 1; y * y <= limit; y++) {
               // condition 1
                  int n = (4 * currentX * currentX) + (y * y);
                  if (n <= limit && (n % 12 == 1 || n % 12 == 5))
                     arr[n] = !arr[n];
               
               // condition 2
                  n = (3 * currentX * currentX) + (y * y);
                  if (n <= limit && n % 12 == 7)
                     arr[n] = !arr[n];
               
               // condition 3
                  n = (3 * currentX * currentX) - (y * y);
                  if (currentX > y && n <= limit && n % 12 == 11)
                     arr[n] = !arr[n];
               }
               return null;
            });
         futures.add(future);
         xLimit[0]++;
      }
      
      // Wait for all tasks to complete
      try {
         for (Future<Void> future : futures) {
            future.get();
         }
      } catch (InterruptedException | ExecutionException e) {
         e.printStackTrace();
      }
      
      executor.shutdown();
      
      // Mark all multiples of squares as non-prime (this part is sequential)
      for (int i = 5; i * i <= limit; i++) {
         if (!arr[i]) 
            continue;
         for (int j = i * i; j <= limit; j += i * i)
            arr[j] = false;
      }
      
      // store all prime numbers
      ArrayList<Integer> primes = new ArrayList<>();
      for (int i = 2; i <= limit; i++) {
         if (arr[i]) {
            primes.add(i);
         }
      }
      
      long endTime = System.nanoTime();
      float elapsedTime = (float)(endTime - startTime) / 1_000_000_000.0f;
      System.out.println("Sieve of Atkin parallel execution time: " + elapsedTime + " seconds");
      
      return primes;
   }
   
   // Method to write primes to file using buffered writer
   static void writePrimesToFile(ArrayList<Integer> primes, String filename) {
      try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
         for (int prime : primes) {
            writer.write(String.valueOf(prime));
            writer.newLine();
         }
         System.out.println("Primes written to file: " + filename);
      } catch (IOException e) {
         System.err.println("Error writing to file: " + e.getMessage());
      }
   }
   
   public static void main(String[] args) {
      long startTime = System.nanoTime();
      
      int limit = 1000000000;
      ArrayList<Integer> primes = sieveOfAtkinParallel(limit);
      
      long endTime = System.nanoTime();
      float totalElapsedTime = (float)(endTime - startTime) / 1_000_000_000.0f;
      
      System.out.println("Total execution time: " + totalElapsedTime + " seconds");
      System.out.println("Total number of primes found: " + primes.size());
      
      // Write primes to file using buffered writer
      writePrimesToFile(primes, "primes.txt");
      
      // Show first 10 primes
      System.out.println("\nFirst 10 primes:");
      int count = Math.min(10, primes.size());
      for (int i = 0; i < count; i++) {
         System.out.print(primes.get(i) + " ");
      }
      System.out.println();
      
      // Show last 10 primes
      System.out.println("\nLast 10 primes:");
      if (primes.size() >= 10) {
         for (int i = primes.size() - 10; i < primes.size(); i++) {
            System.out.print(primes.get(i) + " ");
         }
      } else {
         for (int i = 0; i < primes.size(); i++) {
            System.out.print(primes.get(i) + " ");
         }
      }
      System.out.println();
   }
}
