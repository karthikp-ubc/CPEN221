import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CPEN 221 - L15, slides 36-46: the library pipeline, built one stage at a
 * time, plus Class Activity 2.
 *
 * Run with:  java LibraryExample.java
 */
public class LibraryExample {

    public enum Gender { FEMALE, MALE, OTHER }

    public static class Author {
        private final String firstname;
        private final String lastname;
        private final int age;
        private final Gender gender;

        public Author(String firstname, String lastname, int age, Gender gender) {
            this.firstname = firstname;
            this.lastname  = lastname;
            this.age       = age;
            this.gender    = gender;
        }

        public String getFirstname() { return this.firstname; }
        public String getLastname()  { return this.lastname;  }
        public int    getAge()       { return this.age;       }
        public Gender getGender()    { return this.gender;    }

        @Override public String toString() {
            return firstname + " " + lastname + "(" + age + ")";
        }
    }

    public static class Book {
        private final String title;
        private final Author author;

        public Book(String title, Author author) {
            this.title  = title;
            this.author = author;
        }

        public String getTitle()  { return this.title;  }
        public Author getAuthor() { return this.author; }

        @Override public String toString() { return "\"" + title + "\""; }
    }

    public static void main(String[] args) {

        Author leGuin    = new Author("Ursula",  "Le Guin",   81, Gender.FEMALE);
        Author asimov    = new Author("Isaac",   "Asimov",    72, Gender.MALE);
        Author butler    = new Author("Octavia", "Butler",    58, Gender.FEMALE);
        Author chiang    = new Author("Ted",     "Chiang",    56, Gender.MALE);
        Author leckie    = new Author("Ann",     "Leckie",    48, Gender.FEMALE);
        Author okorafor  = new Author("Nnedi",   "Okorafor",  42, Gender.FEMALE);
        Author chambers  = new Author("Becky",   "Chambers",  39, Gender.FEMALE);
        Author onyebuchi = new Author("Tochi",   "Onyebuchi", 36, Gender.MALE);

        List<Book> library = Arrays.asList(
                new Book("A Wizard of Earthsea",   leGuin),
                new Book("The Dispossessed",       leGuin),     // same author twice
                new Book("Foundation",             asimov),
                new Book("Kindred",                butler),
                new Book("Exhalation",             chiang),
                new Book("Ancillary Justice",      leckie),
                new Book("Who Fears Death",        okorafor),
                new Book("A Long Way to a Small, Angry Planet", chambers),
                new Book("Riot Baby",              onyebuchi));

        System.out.println("library (" + library.size() + " books): " + library);

        // -------- the pipeline, one stage at a time (slides 37-42) --------
        // Problem: the last names of all authors aged 50 and over, in uppercase.

        System.out.println();
        System.out.println("-- stage 1: .map(book -> book.getAuthor()) --");
        System.out.println(library.stream()
                .map(book -> book.getAuthor())
                .collect(Collectors.toList()));

        System.out.println();
        System.out.println("-- stage 2: + .filter(author -> author.getAge() >= 50) --");
        System.out.println(library.stream()
                .map(book -> book.getAuthor())
                .filter(author -> author.getAge() >= 50)
                .collect(Collectors.toList()));

        System.out.println();
        System.out.println("-- stage 3: + .map(Author::getLastname) --");
        System.out.println(library.stream()
                .map(book -> book.getAuthor())
                .filter(author -> author.getAge() >= 50)
                .map(Author::getLastname)
                .collect(Collectors.toList()));

        System.out.println();
        System.out.println("-- stage 4: + .map(String::toUpperCase), then collect --");
        List<String> names = library.stream()
                .map(book -> book.getAuthor())
                .filter(author -> author.getAge() >= 50)
                .map(Author::getLastname)
                .map(String::toUpperCase)
                .collect(Collectors.toList());
        System.out.println(names);
        System.out.println("   LE GUIN appears twice - she wrote two of the books");

        System.out.println();
        System.out.println("-- stage 5: + .distinct().limit(3) --");
        List<String> distinctNames = library.stream()
                .map(book -> book.getAuthor())
                .filter(author -> author.getAge() >= 50)
                .map(Author::getLastname)
                .map(String::toUpperCase)
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
        System.out.println(distinctNames);

        // -------- Class Activity 2 (slides 45-46) --------
        // The minimum age of all FEMALE authors aged between 25 and 50 inclusive.

        System.out.println();
        System.out.println("-- Class Activity 2 --");
        Optional<Integer> minAge =
                library.stream().map(Book::getAuthor)
                        .filter(a -> a.getGender() == Gender.FEMALE)
                        .map(Author::getAge)
                        .filter(age -> age >= 25)
                        .filter(age -> age <= 50)
                        .reduce(Integer::min);
        System.out.println("minimum qualifying age   : " + minAge);

        // Nobody matches: Optional says so, a sentinel would not.
        Optional<Integer> noneMatch =
                library.stream().map(Book::getAuthor)
                        .filter(a -> a.getGender() == Gender.FEMALE)
                        .map(Author::getAge)
                        .filter(age -> age >= 100)
                        .reduce(Integer::min);
        System.out.println("same query, nobody over 100: " + noneMatch);
        System.out.println("   Optional.empty, not a number that looks like an age");
    }
}
