import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * CPEN 221 - L15: streams are lazy.
 *
 * Nothing runs until a terminal operation asks for something, and a
 * short-circuiting terminal operation stops as soon as it has an answer.
 *
 * Run with:  java LazinessExample.java
 */
public class LazinessExample {

    public static class Record {
        private final String id;
        private final String name;

        public Record(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId()   { return this.id;   }
        public String getName() { return this.name; }

        @Override public String toString() { return id + " (" + name + ")"; }
    }

    public static void main(String[] args) {

        List<Record> records = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Record(String.format("R-%04d", i), "record-" + i))
                .collect(Collectors.toList());

        final String wanted = "R-0004";

        // ---- 1. with no terminal operation, nothing runs at all ----
        System.out.println("=== 1. A pipeline with no terminal operation ===");
        Stream<Record> pipeline = records.stream()
                .peek(r -> System.out.println("   examining " + r.getId()))
                .filter(r -> r.getId().equals(wanted));
        System.out.println("   built the pipeline: " + (pipeline != null));
        System.out.println("   nothing was examined - no terminal operation has asked for anything");
        System.out.println();

        // ---- 2. findFirst stops at the match ----
        System.out.println("=== 2. findFirst: short-circuits ===");
        Optional<Record> found = records.stream()
                .peek(r -> System.out.println("   examining " + r.getId()))
                .filter(r -> r.getId().equals(wanted))
                .findFirst();
        System.out.println("   found: " + found.orElse(null));
        System.out.println("   four elements examined out of ten - it stopped as soon as it could");
        System.out.println();

        // ---- 3. filter + collect walks the whole source ----
        System.out.println("=== 3. filter + collect: no short-circuit ===");
        List<Record> matches = records.stream()
                .peek(r -> System.out.println("   examining " + r.getId()))
                .filter(r -> r.getId().equals(wanted))
                .collect(Collectors.toList());
        System.out.println("   found: " + (matches.isEmpty() ? null : matches.get(0)));
        System.out.println("   all ten examined - collect has to finish the stream before it");
        System.out.println("   can hand back a list, even though the answer was known at four");
        System.out.println();

        // ---- 4. the same difference, at a scale you can feel ----
        System.out.println("=== 4. the same difference on 20 million elements ===");
        final int n = 20_000_000;

        long t0 = System.nanoTime();
        OptionalInt first = IntStream.range(1, n)
                                     .filter(i -> i % 1000 == 0)
                                     .findFirst();
        long t1 = System.nanoTime();

        OptionalInt last = IntStream.range(1, n)
                                    .filter(i -> i % 1000 == 0)
                                    .max();
        long t2 = System.nanoTime();

        System.out.printf("   findFirst -> %s   in %5d ms  (stopped at the first match)%n",
                first, (t1 - t0) / 1_000_000);
        System.out.printf("   max       -> %s in %5d ms  (had to see every element)%n",
                last, (t2 - t1) / 1_000_000);
        System.out.println();
        System.out.println("   Same source, same filter. The terminal operation decides how much work happens.");
        System.out.println("   Short-circuiting terminals: findFirst, findAny, anyMatch, allMatch,");
        System.out.println("   noneMatch, limit. Everything else consumes the whole stream.");
    }
}
