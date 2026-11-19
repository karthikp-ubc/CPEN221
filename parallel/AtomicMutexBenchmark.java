import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class AtomicMutexBenchmark {

    private static int ITERATIONS_PER_THREAD = 100; // overridable from the command line
    private static final int MAX_DELAY_CS = 10;
    private static final int MAX_DELAY_MS = 10;

    // Syntax: [thread_counts e.g. 1,2,4] [iterations_per_thread] [warmup_runs] [timed_runs]
    // Backoff (Thread.onSpinWait) is chosen with -Dmutex.backoff=true.
    public static void main(String[] args) {
        int[] threadCounts = args.length > 0 ? parseList(args[0]) : new int[] { 1, 2, 4, 8, 16, 32, 64 };
        if (args.length > 1)
            ITERATIONS_PER_THREAD = Integer.parseInt(args[1]);
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int trials = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        System.out.println("Starting AtomicMutex Benchmark...");
        System.out.printf("Backoff (Thread.onSpinWait): %b, %d iterations per thread, "
                + "median of %d runs after %d warm-up runs%n",
                AtomicMutex.BACKOFF, ITERATIONS_PER_THREAD, trials, warmup);
        System.out.printf("%-15s %-15s%n", "Threads", "Median (ms)");
        System.out.println("-------------------------------");

        for (int numThreads : threadCounts) {
            for (int i = 0; i < warmup; i++) // discarded
                runBenchmark(numThreads);
            double[] durations = new double[trials];
            for (int i = 0; i < trials; i++)
                durations[i] = runBenchmark(numThreads);
            System.out.printf("%-15d %-15.3f%n", numThreads, median(durations));
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

    private static double runBenchmark(int numThreads) {
        AtomicMutex mutex = new AtomicMutex();
        List<Thread> threads = new ArrayList<>();

        // Create threads
        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                Random random = new Random();
                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                    try {
                        // Random delay before accessing the lock
                        Thread.sleep(random.nextInt(MAX_DELAY_MS + 1));

                        mutex.lock();
                        try {
                            // Critical section (sleep for random time)
                            Thread.sleep(random.nextInt(MAX_DELAY_CS + 1));
                        } finally {
                            mutex.unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads.add(t);
        }

        long startTime = System.nanoTime();

        // Start all threads
        for (Thread t : threads) {
            t.start();
        }

        // Wait for all threads to finish
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long endTime = System.nanoTime();
        return (endTime - startTime) / 1e6;
    }
}
