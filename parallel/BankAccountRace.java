import java.util.concurrent.*;

/**
 * CPEN 221 - L20: the bank account race, run for real.
 *
 *   java BankAccountRace plain         slides 6-8   : the lost withdraw
 *   java BankAccountRace rearranged    slide 9      : the "incorrect fix", and a negative balance
 *   java BankAccountRace busyflag      slides 11-12 : rolling your own mutual exclusion
 *   java BankAccountRace synchronized  slides 19-24 : what actually works
 *   java BankAccountRace all           every one, side by side
 */
public class BankAccountRace {

    static class WithdrawTooLargeException extends RuntimeException {}

    interface Account {
        void withdraw(int amount);
        int  getBalance();
        void setStart(int b);
    }

    /** Exactly as it appears on slide 6. */
    static class Plain implements Account {
        private int balance = 0;
        public int  getBalance()      { return balance; }
        public void setBalance(int x) { balance = x; }
        public void setStart(int b)   { balance = b; }
        public void withdraw(int amount) {
            int b = getBalance();
            if (amount > b) throw new WithdrawTooLargeException();
            setBalance(b - amount);
        }
    }

    /** The "incorrect fix" on slide 9: check and subtract read the balance twice. */
    static class Rearranged implements Account {
        private int balance = 0;
        public int  getBalance()      { return balance; }
        public void setBalance(int x) { balance = x; }
        public void setStart(int b)   { balance = b; }
        public void withdraw(int amount) {
            if (amount > getBalance()) throw new WithdrawTooLargeException();
            setBalance(getBalance() - amount);
        }
    }

    /**
     * Slide 9 again, with Thread.yield() dropped into the gap between the check and
     * the subtraction. Same bug - the window is just wide enough to see on few cores.
     */
    static class RearrangedWidened implements Account {
        private int balance = 0;
        public int  getBalance()      { return balance; }
        public void setBalance(int x) { balance = x; }
        public void setStart(int b)   { balance = b; }
        public void withdraw(int amount) {
            if (amount > getBalance()) throw new WithdrawTooLargeException();
            Thread.yield();                       // <-- the only addition
            setBalance(getBalance() - amount);
        }
    }

    /** Slide 11: our own mutual-exclusion protocol. */
    static class BusyFlag implements Account {
        private int balance = 0;
        private boolean busy = false;
        public int  getBalance()      { return balance; }
        public void setBalance(int x) { balance = x; }
        public void setStart(int b)   { balance = b; }
        public void withdraw(int amount) {
            while (busy) { /* spin-wait */ }
            busy = true;
            int b = getBalance();
            if (amount > b) { busy = false; throw new WithdrawTooLargeException(); }
            setBalance(b - amount);
            busy = false;
        }
    }

    /** Slide 24. */
    static class Sync implements Account {
        private int balance = 0;
        public synchronized int  getBalance()      { return balance; }
        public synchronized void setBalance(int x) { balance = x; }
        public void setStart(int b)                { balance = b; }
        public synchronized void withdraw(int amount) {
            int b = getBalance();
            if (amount > b) throw new WithdrawTooLargeException();
            setBalance(b - amount);
        }
    }

    static Account make(String mode) {
        switch (mode) {
            case "plain":        return new Plain();
            case "rearranged":   return new Rearranged();
            case "rearranged-widened": return new RearrangedWidened();
            case "busyflag":     return new BusyFlag();
            case "synchronized": return new Sync();
            default: throw new IllegalArgumentException(mode);
        }
    }

    static final int THREADS = 8;
    static final int OPS     = 200_000;
    static final int RUNS    = 10;
    static final long TIMEOUT_S = 6;

    /** Every thread withdraws 1, OPS times. If nothing is lost the balance ends at 0. */
    static void lostUpdate(String mode) throws Exception {
        System.out.println("--- " + mode + ": " + THREADS + " threads x " + OPS
                           + " withdrawals of $1, starting balance $" + (THREADS * OPS));
        int bad = 0, stuck = 0;
        long worst = 0;
        for (int run = 1; run <= RUNS; run++) {
            final Account acct = make(mode);
            acct.setStart(THREADS * OPS);
            final CountDownLatch go = new CountDownLatch(1);
            Thread[] ts = new Thread[THREADS];
            for (int t = 0; t < THREADS; t++) {
                ts[t] = new Thread(() -> {
                    try { go.await(); } catch (InterruptedException e) { return; }
                    for (int i = 0; i < OPS; i++) {
                        try { acct.withdraw(1); } catch (WithdrawTooLargeException e) { /* ignore */ }
                    }
                });
                ts[t].setDaemon(true);       // so a thread stuck spinning cannot hold the JVM open
                ts[t].start();
            }
            long deadline = System.currentTimeMillis() + TIMEOUT_S * 1000;
            go.countDown();
            boolean alive = false;
            for (Thread t : ts) {
                long left = deadline - System.currentTimeMillis();
                if (left > 0) t.join(left);
                if (t.isAlive()) alive = true;
            }
            if (alive) {
                stuck++;
                System.out.printf("  run %2d: STILL SPINNING after %ds - threads never got out of the wait loop%n",
                                  run, TIMEOUT_S);
                continue;
            }
            int leftOver = acct.getBalance();
            if (leftOver != 0) { bad++; worst = Math.max(worst, leftOver); }
            System.out.printf("  run %2d: final balance $%-9d %s%n", run, leftOver,
                              leftOver == 0 ? "" : "<-- $" + leftOver + " never came out");
        }
        System.out.print("  " + bad + " of " + RUNS + " runs lost money");
        if (worst > 0) System.out.print(", worst was $" + worst + " of $" + (THREADS * OPS) + " left behind");
        if (stuck > 0) System.out.print("; " + stuck + " run(s) hung outright");
        System.out.println();
        System.out.println();
    }

    /** All threads call withdraw(whole balance) at the same instant, many times over. */
    static void negative(String mode) throws Exception {
        final int START  = 100;
        final int TRIALS = 20_000;
        System.out.println("--- " + mode + ": balance $" + START + ", " + THREADS
                           + " threads all call withdraw($" + START + ") at once, " + TRIALS + " times");
        final Account acct = make(mode);
        acct.setStart(START);
        final java.util.concurrent.atomic.AtomicInteger negatives = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger lowest    = new java.util.concurrent.atomic.AtomicInteger();
        // The barrier action inspects the previous round, then resets for the next one.
        CyclicBarrier barrier = new CyclicBarrier(THREADS, () -> {
            int b = acct.getBalance();
            if (b < 0) {
                negatives.incrementAndGet();
                lowest.updateAndGet(v -> Math.min(v, b));
            }
            acct.setStart(START);
        });
        Thread[] ts = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i <= TRIALS; i++) {
                    try { barrier.await(); } catch (Exception e) { return; }
                    try { acct.withdraw(START); } catch (WithdrawTooLargeException e) { /* expected */ }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join(TIMEOUT_S * 1000);
        System.out.printf("  negative balance in %d of %d rounds%s%n",
                          negatives.get(), TRIALS,
                          negatives.get() > 0 ? "   (lowest seen: $" + lowest.get() + ")" : "");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        System.out.println("available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();
        String[] modes = mode.equals("all")
                ? new String[]{"plain", "rearranged", "rearranged-widened", "busyflag", "synchronized"}
                : new String[]{mode};
        for (String m : modes) lostUpdate(m);
        System.out.println("=== and now the check-then-act gap (slide 9) ===");
        System.out.println();
        for (String m : modes) negative(m);
    }
}
