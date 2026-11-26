# Lock granularity: a controlled experiment  (CPEN 221, L21 slides 28-30)

## Why these files exist

`HashTablePerformanceTest.java` in the repo sweeps the **bucket count**
(1, 2, 4 ... 1024) and labels the 1-bucket row "coarse-grained". That
confounds two different things: with 1 bucket you get one lock *and* one
100,000-element linked list, so the numbers are dominated by list
traversal rather than by lock contention.

Measured single-threaded, where no lock can possibly contend:

    Buckets    chain len     10k ops, 1 thread
    1          100000        3882 ms
    2           50000        3484 ms
    4           25000        3202 ms
    16           6250         797 ms
    1024           97          48 ms

An 81x spread with one thread. The same effect is visible in the N=1
column of the table on slide 30 (3667 ms -> 79 ms).

## What these files do instead

Hold the bucket count **fixed** and vary only where `synchronized` sits.
`CoarseGrainedHashTable` and `FineGrainedHashTable` are line-for-line
identical apart from that, so every difference is contention.

    ./demo.sh HashTableLockBenchmark.java      # both sweeps + figures
    # or one point by hand:
    #   java HashTableLockBenchmark <mode> <buckets> <keys> <ops> <threads> <trials> <warmup>
    #   mode = coarse | fine | counted-locked | counted-atomic | counted-unsafe

## Result (2 cores, 1024 buckets fixed, 4096 keys, median of 5)

    median (ms)              1T      2T      4T      8T
    ----------------------------------------------------
    coarse (1 table lock)    37     259     623    1094
    fine (1 lock/bucket)     39     114     255     488
    fine + locked count      41     164     342     770
    fine + atomic count      39     172     332     582

Three things to draw out:

1. **At 1 thread all four are the same.** Lock granularity is free when
   nobody contends -- which is exactly Guideline #2.
2. **Under contention coarse is 2.2-2.4x slower**, at every thread count.
3. **The table-wide `numElements` gives back about half the gain**
   (770 vs 488 at 8T). That is slide 29, question 3, measured.

An `AtomicInteger` counter helps but does not fix it (582 vs 488): it
removes the mutual exclusion but not the cache-line traffic on a single
hot counter. The real fix is per-bucket counts summed on demand, which
is what `ConcurrentHashMap` does -- the same striping idea as L20.

## Caveat

Run on 2 cores, so every multi-thread column is oversubscribed and the
absolute scaling means nothing. The valid comparison is **between rows at
the same thread count**. Re-run on your laptop for numbers to put on the
slide.
