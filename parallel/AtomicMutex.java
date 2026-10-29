import java.util.concurrent.atomic.AtomicInteger;

/**
 * A non-reentrant mutex implementation using AtomicInteger and Compare-and-Swap
 * (CAS).
 */
public class AtomicMutex {
    // State representation: 0 means unlocked, 1 means locked
    private final AtomicInteger state = new AtomicInteger(0);
    public static final Boolean BACKOFF = false;

    /**
     * Acquires the lock.
     * Uses a spin-wait loop until the lock is successfully acquired using CAS.
     */
    public void lock() {
        // Try to change the state from UNLOCKED (0) to LOCKED (1)
        // The compareAndSet method is atomic.
        while (!state.compareAndSet(0, 1)) {
            // If the CAS operation fails, another thread holds the lock.
            // The current thread spins (loops) and retries until successful.
            // Add a hint to the CPU to yield the current thread's time slice if backoff is
            // enabled.
            if (BACKOFF) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Releases the lock.
     * Assumes the current thread holds the lock (non-reentrant).
     */
    public void unlock() {
        // Change the state from LOCKED (1) back to UNLOCKED (0)
        state.set(0);
    }
}
