// Parallel Sum using separate and same threads for left and right

import java.util.Arrays;
import java.util.Random; 
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.ForkJoinPool;

class SumArray extends RecursiveTask<Long> {
    // Uses a thread pool instead of individual threads

    int lo; int hi; int[] arr; // arguments
    long ans = 0; // result
    public static long sequentialCutoff = 10000;

    SumArray(int[] a, int l, int h) { 
        arr = a;
        lo = l;
        hi = h;
    }
    
    protected Long compute(){ // function to override
        
        if(hi - lo < sequentialCutoff) {
            long ans = 0;
            for(int i=lo; i < hi; i++)
                ans += arr[i];
            return ans;
        } else {
            SumArray left = new SumArray(arr,lo,(hi+lo)/2);
            SumArray right= new SumArray(arr,(hi+lo)/2,hi);
            left.fork();
            long rightAns = right.compute();
            long leftAns  = left.join();
            return leftAns + rightAns;
        }
    }
}


public class ArraySum2 {

    static final ForkJoinPool fjPool = new ForkJoinPool();
    
    static long parallelSum(int[] arr) {
        // Parallel sum of the array arr
        return ForkJoinPool.commonPool().invoke
            (new SumArray(arr, 0,arr.length));
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
   
    static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 1) ? s[n/2] : (s[n/2 - 1] + s[n/2]) / 2;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Syntax: array_length num_trials <sequential_cutoff> <warmup_runs>");
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
            SumArray.sequentialCutoff = Long.parseLong(args[2]);

        int warmup = (args.length>3) ? Integer.parseInt(args[3]) : 0;

        // Warm-up runs are discarded: they let the JIT compile the hot code first
        for (int i=0; i<warmup; ++i) {
            parallelSum(arr);
            sequentialSum(arr);
        }

        // Compute the sum in parallel multiple times and take the median duration
        double[] parallelDurations = new double[numTrials];
        for (int i=0; i<numTrials; ++i) {
            long startTime = System.nanoTime();
            ps = parallelSum(arr);
            long endTime = System.nanoTime();
            parallelDurations[i] = (endTime - startTime) / 1e6;
        }

        // Compute the sum sequentially multiple times and take the median duration
        double[] sequentialDurations = new double[numTrials];
        for (int i=0; i<numTrials; ++i) {
            long startTime = System.nanoTime();
            ss = sequentialSum(arr);
            long endTime = System.nanoTime();
            sequentialDurations[i] = (endTime - startTime) / 1e6;
        }

        // Print the statistics for sequential and parallel duration
        System.out.println("Parallel sum = " +  ps);
        System.out.println("Sequential sum = " + ss);
        System.out.println("Median sequential Duration (ms) = " + median(sequentialDurations));
        System.out.println("Median parallel Duration (ms) = " + median(parallelDurations));
        if (ps != ss) {
            System.out.println("WRONG ANSWER: parallel and sequential sums differ");
            System.exit(2);
        }
    }

}
