import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolates LOCK GRANULARITY by holding the bucket count fixed.
 * Only the placement of `synchronized` differs between coarse and fine,
 * so every difference between them is contention, not data-structure cost.
 *
 * Syntax: java HashTableLockBenchmark mode buckets keys ops threads trials warmup
 *   mode     coarse | fine | counted-locked | counted-atomic | counted-unsafe
 *   buckets  bucket count (fixed across a thread sweep)
 *   keys     key-space size; all keys are pre-loaded (untimed) into a fresh table per run
 *   ops      operations per thread per run: 1 in 8 is a write, the rest are gets
 *   threads  worker threads, released together by a CyclicBarrier
 *   trials   timed runs; the median is printed
 *   warmup   untimed runs first, discarded
 *
 * Gets draw keys uniformly from the pre-loaded key space. Writes alternate
 * insert / remove of a scratch key private to the thread (also uniform over
 * the buckets), so the table size really changes and a table-wide numElements
 * is touched on every write. Pre-loaded keys are never removed.
 */
public class HashTableLockBenchmark {

    interface Table {
        boolean put(Integer k, Integer v);
        Integer get(Integer k);
        boolean remove(Integer k);
        default int numElements() { return -1; }   // -1: this table keeps no count
    }

    static Table make(String mode, int buckets) {
        switch (mode) {
            case "coarse": {
                CoarseGrainedHashTable<Integer, Integer> t = new CoarseGrainedHashTable<>(buckets);
                return new Table() {
                    public boolean put(Integer k, Integer v) { return t.put(k, v); }
                    public Integer get(Integer k) { return t.get(k); }
                    public boolean remove(Integer k) { return t.remove(k); }
                };
            }
            case "fine": {
                FineGrainedHashTable<Integer, Integer> t = new FineGrainedHashTable<>(buckets);
                return new Table() {
                    public boolean put(Integer k, Integer v) { return t.put(k, v); }
                    public Integer get(Integer k) { return t.get(k); }
                    public boolean remove(Integer k) { return t.remove(k); }
                };
            }
            case "counted-locked": return counted(buckets, CountedHashTable.Counter.LOCKED);
            case "counted-atomic": return counted(buckets, CountedHashTable.Counter.ATOMIC);
            case "counted-unsafe": return counted(buckets, CountedHashTable.Counter.UNSAFE);
            default: throw new IllegalArgumentException("unknown mode: " + mode
                    + " (coarse | fine | counted-locked | counted-atomic | counted-unsafe)");
        }
    }

    static Table counted(int buckets, CountedHashTable.Counter m) {
        CountedHashTable<Integer, Integer> t = new CountedHashTable<>(buckets, m);
        return new Table() {
            public boolean put(Integer k, Integer v) { return t.put(k, v); }
            public Integer get(Integer k) { return t.get(k); }
            public boolean remove(Integer k) { return t.remove(k); }
            public int numElements() { return t.size(); }
        };
    }

    /** One run on a fresh, pre-loaded table. Returns the timed duration in ms. */
    static double run(Table t, int keys, int ops, int threads) throws Exception {
        for (int k = 0; k < keys; k++) t.put(k, k);              // pre-load (untimed)
        CyclicBarrier go = new CyclicBarrier(threads + 1);
        Thread[] ws = new Thread[threads];
        Throwable[] died = new Throwable[1];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ws[i] = new Thread(() -> {
                try {
                    Random r = new Random(id);
                    int scratchBase = keys * (id + 1);           // disjoint from keys and other threads
                    Integer held = null;                         // scratch key currently in the table
                    go.await();
                    for (int j = 0; j < ops; j++) {
                        if ((j & 7) == 0) {                      // ~12% writes
                            if (held == null) { held = scratchBase + r.nextInt(keys); t.put(held, j); }
                            else              { t.remove(held); held = null; }
                        } else {
                            t.get(r.nextInt(keys));
                        }
                    }
                    if (held != null) t.remove(held);            // leave exactly `keys` entries
                } catch (Throwable e) {
                    died[0] = e;
                }
            });
            ws[i].start();
        }
        go.await();
        long t0 = System.nanoTime();
        for (Thread w : ws) w.join();
        long t1 = System.nanoTime();
        if (died[0] != null) throw new RuntimeException(died[0]);
        return (t1 - t0) / 1e6;
    }

    /** Returns null if the table is intact, else what is wrong with it. */
    static String check(Table t, int keys) {
        int missing = 0;
        for (int k = 0; k < keys; k++)
            if (!Integer.valueOf(k).equals(t.get(k))) missing++;   // writes never touch these
        int n = t.numElements();
        String bad = "";
        if (missing > 0) bad += missing + " pre-loaded key(s) not retrievable. ";
        if (n >= 0 && n != keys) bad += "numElements = " + n + ", true size = " + keys
                + " (drift " + (n - keys > 0 ? "+" : "") + (n - keys) + "). ";
        return bad.isEmpty() ? null : bad;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("usage: java HashTableLockBenchmark <mode> <buckets> <keys> <ops> "
                    + "<threads> <trials> <warmup>");
            System.exit(1);
        }
        String mode = args[0];
        int buckets = Integer.parseInt(args[1]);
        int keys = Integer.parseInt(args[2]);
        int ops = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        int numTrials = Integer.parseInt(args[5]);
        int warmup = Integer.parseInt(args[6]);
        make(mode, buckets);                                     // reject a bad mode up front

        for (int i = 0; i < warmup; i++)                         // discarded: lets the JIT compile
            run(make(mode, buckets), keys, ops, threads);
        double[] durations = new double[numTrials];
        int failed = 0, worst = 0;
        String firstBad = null;
        for (int i = 0; i < numTrials; i++) {
            Table t = make(mode, buckets);
            durations[i] = run(t, keys, ops, threads);
            String bad = check(t, keys);
            if (bad != null) {
                failed++;
                if (firstBad == null) firstBad = bad;
                int n = t.numElements();
                if (n >= 0 && Math.abs(n - keys) > Math.abs(worst)) worst = n - keys;
            }
        }
        Arrays.sort(durations);
        double median = durations[numTrials / 2];
        if (numTrials % 2 == 0)
            median = (durations[numTrials / 2 - 1] + median) / 2;
        System.out.println(mode + ": " + threads + " threads, " + buckets + " buckets, "
                + keys + " keys, " + ops + " ops/thread");
        System.out.println("Median duration (ms) = " + median);
        if (failed > 0) {
            System.out.println("WRONG ANSWER: " + failed + " of " + numTrials + " trials; worst drift "
                    + (worst > 0 ? "+" : "") + worst + "; first: " + firstBad
                    + (mode.equals("counted-unsafe")
                       ? "EXPECTED for counted-unsafe: the unsynchronized numElements++ / -- lose updates under "
                         + "contention. That lost count is the result, not a bug."
                       : ""));
            System.exit(2);
        }
    }
}

/**
 * Coarse-grained: ONE lock for the entire table.
 * Identical to FineGrainedHashTable except for where `synchronized` sits.
 */
class CoarseGrainedHashTable<K, V> {

    private final Bucket<K, V>[] buckets;
    private final int capacity;

    @SuppressWarnings("unchecked")
    CoarseGrainedHashTable(int capacity) {
        this.capacity = capacity;
        this.buckets = new Bucket[capacity];
        for (int i = 0; i < capacity; i++) buckets[i] = new Bucket<>();
    }

    private int index(K key) { return Math.abs(key.hashCode() % capacity); }

    public synchronized boolean put(K key, V value) { return buckets[index(key)].put(key, value); } // <-- table lock
    public synchronized V       get(K key)          { return buckets[index(key)].get(key); }        // <-- table lock
    public synchronized boolean remove(K key)       { return buckets[index(key)].remove(key); }     // <-- table lock

    static class Bucket<K, V> {
        private final List<Entry<K, V>> entries = new LinkedList<>();

        public boolean put(K key, V value) {
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) { e.value = value; return false; }
            return entries.add(new Entry<>(key, value));
        }

        public V get(K key) {
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) return e.value;
            return null;
        }

        public boolean remove(K key) {
            return entries.removeIf(e -> e.key.equals(key));
        }
    }

    static class Entry<K, V> {
        final K key; V value;
        Entry(K k, V v) { key = k; value = v; }
    }
}

/**
 * Fine-grained: ONE lock per bucket.
 * Identical to CoarseGrainedHashTable except for where `synchronized` sits.
 */
class FineGrainedHashTable<K, V> {

    private final Bucket<K, V>[] buckets;
    private final int capacity;

    @SuppressWarnings("unchecked")
    FineGrainedHashTable(int capacity) {
        this.capacity = capacity;
        this.buckets = new Bucket[capacity];
        for (int i = 0; i < capacity; i++) buckets[i] = new Bucket<>();
    }

    private int index(K key) { return Math.abs(key.hashCode() % capacity); }

    public boolean put(K key, V value) { return buckets[index(key)].put(key, value); }
    public V       get(K key)          { return buckets[index(key)].get(key); }
    public boolean remove(K key)       { return buckets[index(key)].remove(key); }

    static class Bucket<K, V> {
        private final List<Entry<K, V>> entries = new LinkedList<>();

        public synchronized boolean put(K key, V value) {          // <-- bucket lock
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) { e.value = value; return false; }
            return entries.add(new Entry<>(key, value));
        }

        public synchronized V get(K key) {                         // <-- bucket lock
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) return e.value;
            return null;
        }

        public synchronized boolean remove(K key) {                // <-- bucket lock
            return entries.removeIf(e -> e.key.equals(key));
        }
    }

    static class Entry<K, V> {
        final K key; V value;
        Entry(K k, V v) { key = k; value = v; }
    }
}

/**
 * Fine-grained buckets PLUS a table-wide element count.
 * Slide 29, question 3: numElements is touched on every insert and remove.
 *   LOCKED  guard it with the table lock: correct, but serializes every write
 *   ATOMIC  AtomicInteger: correct, no mutual exclusion, still one hot cache line
 *   UNSAFE  plain int, no lock: fast, and WRONG -- concurrent ++ / -- lose updates
 */
class CountedHashTable<K, V> {

    enum Counter { LOCKED, ATOMIC, UNSAFE }

    private final Bucket<K, V>[] buckets;
    private final int capacity;
    private final Counter mode;

    private int numElementsLocked = 0;                       // guarded by `this`
    private final AtomicInteger numElementsAtomic = new AtomicInteger();
    private int numElementsUnsafe = 0;                       // guarded by nothing: a data race

    @SuppressWarnings("unchecked")
    CountedHashTable(int capacity, Counter mode) {
        this.capacity = capacity;
        this.mode = mode;
        this.buckets = new Bucket[capacity];
        for (int i = 0; i < capacity; i++) buckets[i] = new Bucket<>();
    }

    private int index(K key) { return Math.abs(key.hashCode() % capacity); }

    public boolean put(K key, V value) {
        boolean inserted = buckets[index(key)].put(key, value);
        if (inserted) count(+1);
        return inserted;
    }

    public V get(K key) { return buckets[index(key)].get(key); }

    public boolean remove(K key) {
        boolean removed = buckets[index(key)].remove(key);
        if (removed) count(-1);
        return removed;
    }

    private void count(int d) {
        switch (mode) {
            case LOCKED: synchronized (this) { numElementsLocked += d; } break; // <-- serializes EVERY write
            case ATOMIC: numElementsAtomic.addAndGet(d); break;                // <-- no mutual exclusion
            case UNSAFE: numElementsUnsafe += d; break;                        // <-- read-modify-write race
        }
    }

    public int size() {
        switch (mode) {
            case LOCKED: synchronized (this) { return numElementsLocked; }
            case ATOMIC: return numElementsAtomic.get();
            default:     return numElementsUnsafe;
        }
    }

    static class Bucket<K, V> {
        private final List<Entry<K, V>> entries = new LinkedList<>();

        public synchronized boolean put(K key, V value) {
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) { e.value = value; return false; }
            return entries.add(new Entry<>(key, value));
        }

        public synchronized V get(K key) {
            for (Entry<K, V> e : entries)
                if (e.key.equals(key)) return e.value;
            return null;
        }

        public synchronized boolean remove(K key) {
            return entries.removeIf(e -> e.key.equals(key));
        }
    }

    static class Entry<K, V> {
        final K key; V value;
        Entry(K k, V v) { key = k; value = v; }
    }
}
