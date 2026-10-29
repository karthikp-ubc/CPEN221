import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * CPEN 221 - L15, slides 49-52: what goes wrong with a stateful lambda in a
 * parallel stream, and what to do instead.
 *
 * There are two separate bugs:
 *   1. the side effect happens in an unpredictable order   (slide 51)
 *   2. the side effect is not even safe                    (slide 52)
 *
 * Run with:  java ParallelStatefulExample.java
 */
public class ParallelStatefulExample {

    public static void main(String[] args) {
        System.out.println("available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        bugOne_order();
        bugTwo_race();
        theFix();
    }

    /** Bug 1: forEachOrdered orders the printing, not the map. */
    static void bugOne_order() {
        System.out.println("=== Bug 1: the order of the side effect ===");
        List<Integer> listOfIntegers = Arrays.asList(8, 7, 6, 5, 4, 3, 2, 1);

        for (int run = 1; run <= 5; run++) {
            List<Integer> serialStorage = new ArrayList<Integer>();

            StringBuilder printed = new StringBuilder();
            listOfIntegers
                    .parallelStream()
                    .map(e -> { serialStorage.add(e); return e; })
                    .forEachOrdered(e -> printed.append(e).append(" "));

            System.out.println("run " + run
                    + "  printed: " + printed.toString().trim()
                    + "   |   serialStorage: " + serialStorage);
        }
        System.out.println("   The printed line is identical every run: forEachOrdered guarantees");
        System.out.println("   encounter order for the TERMINAL action only. It says nothing about");
        System.out.println("   the order map ran in, which is why serialStorage moves around.");
        System.out.println();
    }

    /** Bug 2: ArrayList.add is not thread-safe, so elements go missing. */
    static void bugTwo_race() {
        System.out.println("=== Bug 2: the side effect is not safe ===");
        final int n = 100_000;
        final int runs = 20;

        List<Integer> source = IntStream.rangeClosed(1, n)
                                        .boxed()
                                        .collect(Collectors.toList());

        int damaged = 0;
        for (int run = 0; run < runs; run++) {
            List<Integer> shared = new ArrayList<Integer>();
            try {
                source.parallelStream()
                      .map(e -> { shared.add(e); return e; })
                      .forEach(e -> { });
            } catch (RuntimeException e) {
                // a resize collision can throw outright
                damaged++;
                continue;
            }
            if (shared.size() != n || shared.contains(null)) {
                damaged++;
            }
        }
        System.out.println(damaged + " of " + runs + " runs on " + n + " elements lost elements,");
        System.out.println("left a null behind, or threw. ArrayList.add is roughly");
        System.out.println("    elementData[size++] = e");
        System.out.println("so two threads can read the same size, one write clobbers the other,");
        System.out.println("and size still advances twice - leaving a hole.");
        System.out.println();
    }

    /** The fix: do not accumulate by hand. Let collect do it. */
    static void theFix() {
        System.out.println("=== The fix: collect ===");
        final int n = 100_000;
        List<Integer> source = IntStream.rangeClosed(1, n)
                                        .boxed()
                                        .collect(Collectors.toList());

        for (int run = 1; run <= 3; run++) {
            List<Integer> collected = source.parallelStream()
                                            .map(e -> e * 2)
                                            .collect(Collectors.toList());
            System.out.println("run " + run
                    + "  size: " + collected.size()
                    + "   first three: " + collected.subList(0, 3)
                    + "   in encounter order: " + inOrder(collected));
        }
        System.out.println("   collect accumulates into a container per thread and merges them,");
        System.out.println("   so there is no shared mutable state to race over - and for an");
        System.out.println("   ordered source it still hands back encounter order.");
    }

    static boolean inOrder(List<Integer> doubled) {
        for (int i = 0; i < doubled.size(); i++) {
            if (doubled.get(i) != 2 * (i + 1)) {
                return false;
            }
        }
        return true;
    }
}
