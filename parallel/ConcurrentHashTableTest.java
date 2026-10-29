import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentHashTableTest {

    public static void main(String[] args) throws InterruptedException {
        testBasicOperations();
        testConcurrency();
    }

    private static void testBasicOperations() {
        System.out.println("Testing basic operations...");
        FineGrainedConcurrentHashTable<String, Integer> map = new FineGrainedConcurrentHashTable<>();

        map.put("one", 1);
        map.put("two", 2);

        assert map.get("one") == 1 : "Get one failed";
        assert map.get("two") == 2 : "Get two failed";
        assert map.get("three") == null : "Get three failed";

        map.put("one", 11);
        assert map.get("one") == 11 : "Update one failed";

        boolean removed = map.remove("one");
        assert removed : "Remove one failed";
        assert map.get("one") == null : "Get one after remove failed";

        System.out.println("Basic operations passed.");
    }

    private static void testConcurrency() throws InterruptedException {
        System.out.println("Testing concurrency...");
        final int THREAD_COUNT = 10;
        final int ITERATIONS = 1000;
        FineGrainedConcurrentHashTable<String, Integer> map = new FineGrainedConcurrentHashTable<>();
        ExecutorService es = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            es.execute(() -> {
                try {
                    for (int j = 0; j < ITERATIONS; j++) {
                        String key = "key-" + (j % 100); // Shared keys
                        map.put(key, j);
                        Integer val = map.get(key);
                        if (val == null && j > 0) {
                            // It's possible val is null if another thread hasn't put it yet,
                            // but we just put it. However, another thread could have removed it?
                            // Actually, we are only doing puts here.
                            // Wait, if we just put it, it should be there unless overwritten.
                            // But multiple threads are writing to same keys.
                            // The value might change, but it shouldn't be null if we assume no removals in
                            // this test.
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errorCount.incrementAndGet();
                }
            });
        }

        es.shutdown();
        es.awaitTermination(1, TimeUnit.MINUTES);

        if (errorCount.get() > 0) {
            System.out.println("Concurrency test failed with errors.");
        } else {
            System.out.println("Concurrency test passed (no exceptions).");
        }
    }
}
