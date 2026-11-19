import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HashTablePerformanceTest {

    // NOTE: Increasing this size will result in O(N^2) performance and take an
    // impractical amount of time. Proceed with caution.
    private static int INITIAL_SIZE = 100_000; // overridable from the command line
    private static int OPERATIONS_PER_THREAD = 10_000;

    // Syntax: [bucket_counts e.g. 1,2,4] [thread_counts] [initial_size] [ops_per_thread]
    //         [warmup_runs] [timed_runs]
    public static void main(String[] args) {
        int[] bucketSizes = args.length > 0 ? parseList(args[0])
                : new int[] { 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024 };
        int[] threadCounts = args.length > 1 ? parseList(args[1])
                : new int[] { 1, 2, 4, 8, 16 }; // 32, 64, 128, 256, 512, 1024 };
        if (args.length > 2)
            INITIAL_SIZE = Integer.parseInt(args[2]);
        if (args.length > 3)
            OPERATIONS_PER_THREAD = Integer.parseInt(args[3]);
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        int trials = args.length > 5 ? Integer.parseInt(args[5]) : 5;

        System.out.printf("# %d entries, %d ops per thread, median of %d runs after %d warm-up runs%n",
                INITIAL_SIZE, OPERATIONS_PER_THREAD, trials, warmup);
        System.out.printf("%-12s %-12s %-15s%n", "Buckets", "Threads", "Median (ms)");
        System.out.println("-----------------------------------------");

        for (int buckets : bucketSizes) {
            // Build one table per bucket count and reuse it for every thread count.
            // The timed operations only touch keys already in the table, so its size
            // never changes between runs.
            long buildStart = System.nanoTime();
            FineGrainedConcurrentHashTable<Integer, Integer> map = new FineGrainedConcurrentHashTable<>(buckets);
            int[] keys = new int[INITIAL_SIZE];
            Random random = new Random();
            for (int i = 0; i < INITIAL_SIZE; i++) {
                keys[i] = random.nextInt();
                map.put(keys[i], random.nextInt());
            }
            System.out.printf("# built %d buckets in %.0f ms%n", buckets,
                    (System.nanoTime() - buildStart) / 1e6);

            for (int threads : threadCounts) {
                try {
                    for (int i = 0; i < warmup; i++) // discarded
                        runTest(map, keys, threads);
                    double[] durations = new double[trials];
                    for (int i = 0; i < trials; i++)
                        durations[i] = runTest(map, keys, threads);
                    System.out.printf("%-12d %-12d %-15.3f%n", buckets, threads, median(durations));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static int[] parseList(String s) {
        return Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 1) ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    private static double runTest(FineGrainedConcurrentHashTable<Integer, Integer> map, int[] keys,
            int threadCount) throws InterruptedException {

        // We use a thread pool to simulate concurrent access (each thread accesses a
        // random key from the table). We also use a combined mix of get and put
        // operations; puts overwrite existing entries.

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                Random threadRandom = new Random();
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    // Random access: mix of get and put
                    int key = keys[threadRandom.nextInt(keys.length)];
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

        return (endTime - startTime) / 1e6;
    }
}
