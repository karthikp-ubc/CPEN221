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

Each run pre-loads every key into a fresh table, then each thread does
`ops` operations: 7 in 8 are gets on a uniformly random pre-loaded key, and
1 in 8 is a write that alternately inserts and removes a scratch key owned
by that thread. Writes really change the size, so a table-wide
`numElements` is touched on every one. After each timed run the program
checks every pre-loaded key and, for the counted modes, `numElements`
against the true size, and prints `WRONG ANSWER` if either is off.

## Result (16 logical CPUs, 1024 buckets fixed, 4096 keys, 1M ops/thread, median of 15)

From `./demo.sh HashTableLockBenchmark.java --full`, run 20261125-203747
(`results/HashTableLockBenchmark.csv`, `figures/HashTableLockBenchmark_threads.png`).

    median (ms)                      1T     2T     4T     8T    16T    32T    64T
    -----------------------------------------------------------------------------
    coarse (1 table lock)            42     80    371   1245   2473   4831   9730
    fine (1 lock/bucket)             29     34     41     55     94    107    231
    fine + locked count              30     39     69    162    430    858   1676
    fine + atomic count              29     38     53     87    124    145    277
    fine + unsynchronized count      28    41*    58*     94   166*    324   660*

    * numElements was wrong: worst drift -392 (2T), -4028 (4T), -2084 (16T),
      -3918 (64T) against a true size of 4096. 8T and 32T happened to come
      out exact in all 15 trials -- a race that passes a test is still a race.

Four things to draw out:

1. **At 1 thread the variants are close** (29-42 ms). The gap between
   them grows to 42x under contention, so lock granularity costs almost
   nothing when nobody contends -- which is exactly Guideline #2.
2. **Under contention coarse is 9-42x slower than fine** (from 4 threads
   up), and the gap keeps growing with the thread count.
3. **A table-wide `numElements` under a lock gives back most of the gain**:
   430 vs 94 ms at 16T, 1676 vs 231 at 64T. That is slide 29, question 3,
   measured.
4. **Dropping the lock on the counter makes it wrong, not fast.** The
   unsynchronized `int` loses up to almost the whole count, and from 8
   threads up it is *slower* than the `AtomicInteger` (660 vs 277 ms at
   64T): every thread still fights over the same cache line.

An `AtomicInteger` counter is correct and nearly as fast as no counter
(277 vs 231 at 64T): it removes the mutual exclusion but not the
cache-line traffic on a single hot counter. The real fix is per-bucket
counts summed on demand, which is what `ConcurrentHashMap` does -- the
same striping idea as L20.

## Control: what the old bucket sweep was measuring

The same fine table with **one thread** -- so no lock can contend --
and only the bucket count varied (`figures/HashTableLockBenchmark_buckets.png`):

    buckets      1     2     4     8    16    32    64   256  1024  4096
    chain len 4096  2048  1024   512   256   128    64    16     4     1
    median ms 2618  2159  1678   848   274   140    95    50    28    17

A 158x spread with nothing but list traversal changing.

## Caveat

The machine has 16 logical CPUs, so the 32T and 64T columns are
oversubscribed and also measure scheduling. Absolute times depend on the
machine; the valid comparison is **between rows at the same thread
count**. Re-run with `./demo.sh HashTableLockBenchmark.java --full` for
numbers from your own laptop.
