import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * A thread-safe hash table implementation using fine-grained locking.
 * Each bucket is a separate object with synchronized methods, allowing
 * concurrent access to different buckets.
 *
 * @param <K> The type of keys maintained by this map
 * @param <V> The type of mapped values
 */
public class FineGrainedConcurrentHashTable<K, V> {

    private static final int DEFAULT_CAPACITY = 16;
    private final Bucket<K, V>[] buckets;
    private final int capacity;

    public FineGrainedConcurrentHashTable() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public FineGrainedConcurrentHashTable(int capacity) {
        this.capacity = capacity;
        this.buckets = (Bucket<K, V>[]) new Bucket[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Bucket<>();
        }
    }

    private int getBucketIndex(K key) {
        return Math.abs(key.hashCode() % capacity);
    }

    public void put(K key, V value) {
        int index = getBucketIndex(key);
        buckets[index].put(key, value);
    }

    public V get(K key) {
        int index = getBucketIndex(key);
        return buckets[index].get(key);
    }

    public boolean remove(K key) {
        int index = getBucketIndex(key);
        return buckets[index].remove(key);
    }

    /**
     * A bucket in the hash table.
     * Methods are synchronized to ensure thread safety within the bucket.
     */
    private static class Bucket<K, V> {
        private final List<Entry<K, V>> entries;

        public Bucket() {
            this.entries = new LinkedList<>();
        }

        public synchronized void put(K key, V value) {
            for (Entry<K, V> entry : entries) {
                if (entry.key.equals(key)) {
                    entry.value = value;
                    return;
                }
            }
            entries.add(new Entry<>(key, value));
        }

        public synchronized V get(K key) {
            for (Entry<K, V> entry : entries) {
                if (entry.key.equals(key)) {
                    return entry.value;
                }
            }
            return null;
        }

        public synchronized boolean remove(K key) {
            for (Entry<K, V> entry : entries) {
                if (entry.key.equals(key)) {
                    entries.remove(entry);
                    return true;
                }
            }
            return false;
        }
    }

    private static class Entry<K, V> {
        final K key;
        V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
