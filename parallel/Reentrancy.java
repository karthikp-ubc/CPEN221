import java.util.concurrent.locks.ReentrantLock;

/**
 * CPEN 221 - L20, slides 15-18: why the Lock ADT has to be re-entrant.
 *
 * Slide 16 claims that if getBalance/setBalance acquire the same lock as withdraw,
 * then "withdraw would block forever because it tries to acquire a lock it already
 * has". This runs that claim.
 *
 *   java Reentrancy
 */
public class Reentrancy {

    /** The Lock ADT from slide 13, with no notion of who holds it. */
    static class PlainLock {
        private boolean held = false;
        synchronized void acquire() {
            while (held) {
                try { wait(); } catch (InterruptedException e) { return; }
            }
            held = true;
        }
        synchronized void release() { held = false; notifyAll(); }
    }

    /** withdraw() takes the lock, then calls setBalance(), which takes it again. */
    static class NonReentrantAccount {
        private int balance = 100;
        private final PlainLock lk = new PlainLock();

        void setBalance(int x) {
            lk.acquire();
            balance = x;
            lk.release();
        }
        void withdraw(int amount) {
            lk.acquire();
            setBalance(balance - amount);     // <-- second acquire, same thread
            lk.release();
        }
        int getBalance() { return balance; }
    }

    /** The same shape, on Java's synchronized, which is re-entrant. */
    static class SyncAccount {
        private int balance = 100;
        synchronized void setBalance(int x) { balance = x; }
        synchronized void withdraw(int amount) { setBalance(balance - amount); }
        synchronized int getBalance() { return balance; }
    }

    static boolean runWithTimeout(Runnable body, long ms) throws InterruptedException {
        Thread t = new Thread(body);
        t.setDaemon(true);
        t.start();
        t.join(ms);
        return !t.isAlive();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. A lock that does not remember who holds it ===");
        NonReentrantAccount a = new NonReentrantAccount();
        boolean finished = runWithTimeout(() -> a.withdraw(10), 3000);
        if (finished) {
            System.out.println("   withdraw returned, balance = " + a.getBalance());
        } else {
            System.out.println("   withdraw has not returned after 3 s.");
            System.out.println("   It holds the lock and is waiting for the lock. Nobody else can release it.");
            System.out.println("   This is slide 16's \"block forever\", and it is a single thread doing it.");
        }
        System.out.println();

        System.out.println("=== 2. The same code on synchronized ===");
        SyncAccount b = new SyncAccount();
        finished = runWithTimeout(() -> b.withdraw(10), 3000);
        System.out.println("   withdraw " + (finished ? "returned" : "did NOT return")
                           + ", balance = " + b.getBalance());
        System.out.println("   synchronized is re-entrant: the holder may take it again.");
        System.out.println();

        System.out.println("=== 3. The count, made visible ===");
        final ReentrantLock lk = new ReentrantLock();
        System.out.println("   before          : holdCount = " + lk.getHoldCount());
        lk.lock();
        System.out.println("   after 1 lock()  : holdCount = " + lk.getHoldCount());
        lk.lock();
        System.out.println("   after 2 lock()  : holdCount = " + lk.getHoldCount()
                           + "   <- did not block, same thread");
        lk.unlock();
        System.out.println("   after 1 unlock(): holdCount = " + lk.getHoldCount()
                           + "   <- still held");
        lk.unlock();
        System.out.println("   after 2 unlock(): holdCount = " + lk.getHoldCount()
                           + "   <- now free");
        System.out.println();
        System.out.println("   java.util.concurrent.locks.ReentrantLock is the Java class that matches");
        System.out.println("   the Lock ADT on slide 13. synchronized is the same idea with no name.");
    }
}
