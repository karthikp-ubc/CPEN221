# CPEN 221 — L15 Lambdas & Streams: live demos

Six self-contained programs, one per slide group. No package, no build file,
no dependencies. Each runs directly on Java 11+:

    java SumExample.java

or compile first if you prefer:

    javac *.java && java SumExample

Everything is written in Java 8 syntax (no `var`, no `List.of`, no records),
so it matches the deck and runs anywhere the students' toolchain does.

| File | Slides | Shows |
|---|---|---|
| `SumExample.java` | 27 | sum by loop vs by `reduce`; the identity on an empty array; a stream consumed twice throws |
| `ReduceExample.java` | 29–31 | `reduce` with and without an identity; `Optional` vs a sentinel; method references; **why the accumulator must be associative** |
| `MapFilterExample.java` | 32–35 | `map`, `filter`, imperative vs pipeline, and a traced run showing the single pass |
| `LibraryExample.java` | 36–46 | the `Book`/`Author` pipeline built one stage at a time, plus Class Activity 2 |
| `ParallelStatefulExample.java` | 49–52 | both bugs, separately: wrong order, then lost elements — and the `collect` fix |
| `LazinessExample.java` | — | nothing runs without a terminal op; `findFirst` short-circuits, `collect` doesn't |

## Notes for presenting

**ReduceExample** — the associativity section is the one to dwell on. Subtraction
sequential gives `-500500`, parallel gives something else entirely. Addition gives
the same answer either way. That is slide 29's precondition, demonstrated rather
than asserted.

**ParallelStatefulExample** — the two bugs are deliberately separated. Bug 1 runs
five times and prints both the `forEachOrdered` output (identical every run) and
`serialStorage` (different every run). That contrast is the trap: students see a
stable line and conclude the pipeline is ordered, when only the terminal step was.
Bug 2 needs scale, so it runs 100,000 elements twenty times and counts the damaged
runs. On a 2-core machine roughly a quarter of runs lose data; with more cores,
more. Run it twice — the number changes, which is itself the point.

**LazinessExample** — section 1 prints nothing, deliberately. That blank is the
demo. Sections 2 and 3 use `peek` to show four elements examined versus ten.
Section 4 scales it to 20 million so the difference is in milliseconds rather
than in printed lines.

**LibraryExample** — the stages print real intermediate values, so you can show
`[Ursula Le Guin(81), Ursula Le Guin(81), ...]` before `.distinct()` removes the
duplicate. Le Guin has two books in the library for exactly that reason.
