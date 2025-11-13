// Parallel Sum using separate and same threads for left and right

import java.util.Arrays;
import java.util.Random; 
import java.RecursiveTask;

class SumArray extends RecursiveTask<Integer> {
    // Uses a thread pool instead of individual threads

    int lo; int hi; int[] arr; // arguments
    long ans = 0; // result
    public static long sequentialCutoff = 10000;
    public static boolean separateThreads = false;

    SumThread(int[] a, int l, int h) { 
        arr = a;
        lo = l;
        hi = h;
    }
    
    protected Integer compute(){ // function to override
        
        if(hi – lo < sequentialCutoff) {
            int ans = 0;
            for(int i=lo; i < hi; i++)
                ans += arr[i];
            return ans;
        } else {
            SumArray left = new SumArray(arr,lo,(hi+lo)/2);
            SumArray right= new SumArray(arr,(hi+lo)/2,hi);
            left.fork();
            int rightAns = right.compute();
            int leftAns  = left.join();
            return leftAns + rightAns;
        }
    }
}

static final ForkJoinPool fjPool = new ForkJoinPool();
    int sum(int[] arr) {
        return ForkJoinPool.commonPool().invoke
            (new SumArray(arr,0,arr.length));
}

public class ArraySum {

    static long parallelSum(int[] arr) {
        // Parallel sum of the array arr
        SumThread t = new SumThread(arr,0,arr.length);
        t.run();
        return t.ans;
    }

    static long sequentialSum(int[] arr) {
        long result = 0;
        for (int i=0; i<arr.length; ++i)
            result += arr[i];
        return result;
    }

    static void createRandomArray(int[] arr) {
        Random random = new Random();
        for (int i=0; i<arr.length; ++i) {
            arr[i] = random.nextInt(arr.length * 10);
        }
    }
   
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Syntax: array_length num_trials <separate_threads> <sequential_cutoff>");
            System.exit(0);
        }

        int arrayLength = Integer.parseInt(args[0]);
        int numTrials = Integer.parseInt(args[1]);

        // We initialize these here as otherwise Java complains about uninitialized vars
        long ss = 0;
        long ps = 0;

        // Create an array and populate it with random numbers
        int[] arr = new int[arrayLength];
        createRandomArray(arr);

        // Set the parameters for the parallel sum class if the arguments are specified
        if (args.length>2)
            SumThread.separateThreads = Boolean.parseBoolean(args[2]);

        if (args.length>3)
            SumThread.sequentialCutoff = 10000;
        
        // Compute the sum in parallel multiple times and average the duration
        double parallelDuration = 0.0;    
        for (int i=0; i<numTrials; ++i) {
            long startTime = System.nanoTime();
            ps = parallelSum(arr);
            long endTime = System.nanoTime();
            double duration = (endTime - startTime)/1000000;
            parallelDuration += (duration / numTrials);
        }

        // Compute the sum sequentially multiple times and average the duration
        double sequentialDuration = 0.0;    
        for (int i=0; i<numTrials; ++i) {
            long startTime = System.nanoTime();
            ss = sequentialSum(arr);
            long endTime = System.nanoTime();
            double duration = (endTime - startTime)/1000000;
            sequentialDuration += (duration / numTrials);
        }
        
        // Print the statistics for sequential and parallel duration
        System.out.println("Parallel sum = " +  ps);
        System.out.println("Sequential sum = " + ss);
        System.out.println("Average sequential Duration (ms) = " + sequentialDuration);
        System.out.println("Average parallel Duration (ms) = " + parallelDuration);
    }

}
