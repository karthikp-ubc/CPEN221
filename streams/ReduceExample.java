import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * CPEN 221 - L15, slides 29-31: reduce, the identity, Optional,
 * method references, and why the accumulator must be associative.
 *
 * Run with:  java ReduceExample.java
 */
public class ReduceExample {

    public static void main(String[] args) {
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);

        // ---- reduce(identity, accumulator) ----
        System.out.println("-- reduce with an identity --");
        int sum = numbers.stream().reduce(0, (a, b) -> a + b);
        System.out.println("numbers                  : " + numbers);
        System.out.println("sum                      : " + sum);

        int max = numbers.stream().reduce(Integer.MIN_VALUE, (a, b) -> (a > b) ? a : b);
        System.out.println("max, sentinel identity   : " + max);

        // The sentinel is indistinguishable from a real answer when the stream is empty.
        List<Integer> none = Collections.emptyList();
        int maxOfNothing = none.stream().reduce(Integer.MIN_VALUE, (a, b) -> (a > b) ? a : b);
        System.out.println("max of an empty stream   : " + maxOfNothing);
        System.out.println("                           ^ a maximum, or an empty list? You cannot tell.");

        // ---- reduce(accumulator) returns Optional and says so honestly ----
        System.out.println();
        System.out.println("-- reduce without an identity --");
        Optional<Integer> maxOpt     = numbers.stream().reduce(Integer::max);
        Optional<Integer> maxOptNone = none.stream().reduce(Integer::max);
        System.out.println("max as Optional          : " + maxOpt);
        System.out.println("max of nothing, Optional : " + maxOptNone);
        System.out.println("isPresent()              : " + maxOptNone.isPresent());

        // ---- method references (slide 31) ----
        System.out.println();
        System.out.println("-- method references: the same thing, written twice --");
        System.out.println("lambda           : " + numbers.stream().reduce(0, (a, b) -> Integer.sum(a, b)));
        System.out.println("method reference : " + numbers.stream().reduce(0, Integer::sum));

        // ---- why f must be associative (slide 29) ----
        // Subtraction is not associative:  (a - b) - c  !=  a - (b - c)
        System.out.println();
        System.out.println("-- why the accumulator must be associative --");
        List<Integer> big = IntStream.rangeClosed(1, 1000)
                                     .boxed()
                                     .collect(Collectors.toList());

        int minusSerial   = big.stream().reduce(0, (a, b) -> a - b);
        int minusParallel = big.parallelStream().reduce(0, (a, b) -> a - b);
        int plusSerial    = big.stream().reduce(0, Integer::sum);
        int plusParallel  = big.parallelStream().reduce(0, Integer::sum);

        System.out.println("subtraction, sequential  : " + minusSerial);
        System.out.println("subtraction, parallel    : " + minusParallel);
        System.out.println("                           same? " + (minusSerial == minusParallel)
                + "   <- (a-b)-c != a-(b-c), so the split order changes the answer");
        System.out.println("addition,    sequential  : " + plusSerial);
        System.out.println("addition,    parallel    : " + plusParallel);
        System.out.println("                           same? " + (plusSerial == plusParallel)
                + "    <- + is associative, so any split gives the same answer");
    }
}
