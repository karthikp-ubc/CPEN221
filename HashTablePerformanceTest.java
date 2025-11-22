import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HashTablePerformanceTest {

    // NOTE: Increasing this size will result in O(N^2) performance and take an
    // impractical amount of time. Proceed with caution.
    private static final int INITIAL_SIZE = 100_000;
    private static final int OPERATIONS_PER_THREAD = 10_000;

    public static void main(String[] args) {
        int[] bucketSizes = { 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024 };
        int[] threadCounts = { 1, 2, 4, 8, 16 }; // 32, 64, 128, 256, 512, 1024 };

        System.out.printf("%-12s %-12s %-15s%n", "Buckets", "Threads", "Time (ms)");
        System.out.println("-----------------------------------------");

        for (int buckets : bucketSizes) {
            for (int threads : threadCounts) {
                try {
                    runTest(buckets, threads);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void runTest(int bucketSize, int threadCount) throws InterruptedException {

        // Initialize the hash table with the specified number of buckets.
        FineGrainedConcurrentHashTable<Integer, Integer> map = new FineGrainedConcurrentHashTable<>(bucketSize);

        // Initialize a random number generator.
        Random random = new Random();

        // Populate the table (single-threaded for consistency, or multi-threaded if
        // needed).
        // We use a smaller subset here to keep the test runnable.
        for (int i = 0; i < INITIAL_SIZE; i++) {
            map.put(random.nextInt(), random.nextInt());
        }

        // We use a thread pool to simulate concurrent access (each thread accesses a
        // random key). We also use a combined mix of get and put operations.

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                Random threadRandom = new Random();
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    // Random access: mix of get and put
                    int key = threadRandom.nextInt();
                    if (threadRandom.nextBoolean()) {
                        map.get(key);
                    } else {
                        map.put(key, threadRandom.nextInt());
                    }
                }
                return null;
            });
        }

        long startTime = System.nanoTime();
        executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        long endTime = System.nanoTime();

        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        System.out.printf("%-12d %-12d %-15d%n", bucketSize, threadCount, durationMs);
    }
}
