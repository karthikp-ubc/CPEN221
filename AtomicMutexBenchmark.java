import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AtomicMutexBenchmark {

    private static final int ITERATIONS_PER_THREAD = 100;
    private static final int MAX_DELAY_CS = 10;
    private static final int MAX_DELAY_MS = 10;

    public static void main(String[] args) {
        int[] threadCounts = { 1, 2, 4, 8, 16, 32, 64 };

        System.out.println("Starting AtomicMutex Benchmark...");
        System.out.printf("%-15s %-15s%n", "Threads", "Time (ms)");
        System.out.println("-------------------------------");

        for (int numThreads : threadCounts) {
            long duration = runBenchmark(numThreads);
            System.out.printf("%-15d %-15d%n", numThreads, duration);
        }
    }

    private static long runBenchmark(int numThreads) {
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

        long startTime = System.currentTimeMillis();

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

        long endTime = System.currentTimeMillis();
        return endTime - startTime;
    }
}
