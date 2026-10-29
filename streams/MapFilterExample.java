import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CPEN 221 - L15, slides 32-35: map, filter, and the same job written
 * imperatively and as a pipeline.
 *
 * Run with:  java MapFilterExample.java
 */
public class MapFilterExample {

    public static void main(String[] args) {

        // ---- map (slide 32) ----
        System.out.println("-- map --");
        List<String> names = Arrays.asList("alice", "bob", "carol");
        List<String> upper = names.stream()
                                  .map(String::toUpperCase)
                                  .collect(Collectors.toList());
        System.out.println("names  : " + names);
        System.out.println("upper  : " + upper);
        System.out.println("names  : " + names + "   <- unchanged; map produced a new list");

        // ---- filter (slide 33) ----
        System.out.println();
        System.out.println("-- filter --");
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> evens = numbers.stream()
                                     .filter(n -> n % 2 == 0)   // a Predicate<Integer>
                                     .collect(Collectors.toList());
        System.out.println("numbers: " + numbers);
        System.out.println("evens  : " + evens);

        // ---- the same job, two ways (slides 34-35) ----
        System.out.println();
        System.out.println("-- squared evens --");
        System.out.println("imperative : " + squaredEvensImperative(numbers));
        System.out.println("pipeline   : " + squaredEvensPipeline(numbers));

        // ---- the pipeline makes one pass, with no intermediate list ----
        System.out.println();
        System.out.println("-- one pass: watch the interleaving --");
        List<Integer> traced = numbers.stream()
                .filter(n -> {
                    System.out.println("   filter " + n);
                    return n % 2 == 0;
                })
                .map(n -> {
                    System.out.println("        map " + n);
                    return n * n;
                })
                .collect(Collectors.toList());
        System.out.println("result : " + traced);
        System.out.println("   filter and map alternate per element - the stream is not");
        System.out.println("   filtered into a list and then mapped.");
    }

    static List<Integer> squaredEvensImperative(List<Integer> numbers) {
        List<Integer> squaredEvens = new ArrayList<Integer>();
        for (Integer number : numbers) {
            if (number % 2 == 0) {
                squaredEvens.add(number * number);
            }
        }
        return squaredEvens;
    }

    static List<Integer> squaredEvensPipeline(List<Integer> numbers) {
        return numbers.stream()
                      .filter(number -> number % 2 == 0)
                      .map(number -> number * number)
                      .collect(Collectors.toList());
    }
}
