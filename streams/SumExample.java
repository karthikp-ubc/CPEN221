import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * CPEN 221 - L15, slide 27: summing an array by loop and by reduction.
 *
 * Run with:  java SumExample.java      (Java 11+)
 * Or:        javac SumExample.java && java SumExample
 */
public class SumExample {

    /** The imperative version, from the left of the slide. */
    static int sumLoop(int[] arr) {
        int sum = 0;
        for (int x : arr) {
            sum += x;
        }
        return sum;
    }

    /** The reduction version, from the right of the slide. */
    static int sumReduce(int[] arr) {
        IntStream s = Arrays.stream(arr);
        return s.reduce(0, (x, y) -> x + y);   // identity, then accumulator
    }

    /** The same thing, more compactly. */
    static int sumCompact(int[] arr) {
        return Arrays.stream(arr).reduce(0, (x, y) -> x + y);
    }

    public static void main(String[] args) {
        int[] arr = {1, 2, 3, 4, 5};

        System.out.println("array       : " + Arrays.toString(arr));
        System.out.println("sumLoop     : " + sumLoop(arr));
        System.out.println("sumReduce   : " + sumReduce(arr));
        System.out.println("sumCompact  : " + sumCompact(arr));

        // The identity is what comes back when there is nothing to combine.
        int[] empty = {};
        System.out.println();
        System.out.println("empty array : " + Arrays.toString(empty));
        System.out.println("sumReduce   : " + sumReduce(empty) + "        <- the identity, 0");

        // A stream is consumed once (slide 24).
        System.out.println();
        System.out.println("-- a stream is consumed once --");
        IntStream once = Arrays.stream(arr);
        System.out.println("first use   : " + once.reduce(0, (x, y) -> x + y));
        try {
            once.reduce(0, (x, y) -> x + y);
            System.out.println("second use  : no exception?!");
        } catch (IllegalStateException e) {
            System.out.println("second use  : IllegalStateException: " + e.getMessage());
        }
    }
}
