import java.util.Arrays;

/**
 * This class allows us to compute the sum of an array using multiple threads.
 * 
 * @author Sathish Gopalakrishnan
 *
 */
public class SumThread extends Thread {
	// we want to compute ans as sum[lo]+sum[lo+1]...+sum[hi-1]
	private int lo;
	private int hi;
	private long ans = 0;
	private int[] arr;

	/**
	 * 
	 * @param a
	 *            array to sum
	 * @param lo
	 *            is the index to start summing from
	 * @param hi
	 *            is the index to stop computing the sum at (a[hi] is not part
	 *            of the sum)
	 */
	SumThread(int[] a, int lo, int hi) {
		this.arr = a;
		this.lo = lo;
		this.hi = hi;
		this.ans = 0;
	}

	/**
	 * Actually compute the sum
	 */
	public void run() {
		for (int idx = lo; idx < hi; idx++) {
			ans += arr[idx];
		}
		// System.out.println(ans);
	}

	/**
	 * Compute the sum of an array
	 * 
	 * @param arr
	 *            array to sum
	 * @param numTs
	 *            number of threads to split the work across
	 * @return the sum of the elements in arr
	 * @throws InterruptedException
	 */
	public static long sum(int[] arr, int numTs) throws InterruptedException {
		int len = arr.length;
		long ans = 0;
		int numThreads = numTs;
		SumThread[] ts = new SumThread[numThreads];
		for (int i = 0; i < numThreads; i++) {// do parallel computations
			ts[i] = new SumThread(arr, i * len / numThreads, (i + 1) * len / numThreads);
			ts[i].start(); // start not run
		}
		for (int i = 0; i < numThreads; i++) { // combine results
			ts[i].join(); // wait for helper to finish!
			ans += ts[i].ans;
		}
		return ans;
	}

	public static void main(String[] args) {
		// Syntax: [array_length] [num_threads] [num_trials] [warmup_runs]
		int len = args.length > 0 ? Integer.parseInt(args[0]) : 1_024_000;
		int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : 8;
		int numTrials = args.length > 2 ? Integer.parseInt(args[2]) : 1;
		int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 0;
		long ans = 0;
		int[] arr = new int[len];
		for (int i = 0; i < len; i++)
			arr[i] = i + 1;
		long expected = (long) len * (len + 1) / 2;
		double[] durations = new double[numTrials];
		try {
			for (int i = 0; i < warmup; i++) // discarded: lets the JIT compile run()
				sum(arr, numThreads);
			for (int i = 0; i < numTrials; i++) {
				long start = System.nanoTime();
				ans = sum(arr, numThreads);
				long end = System.nanoTime();
				durations[i] = (end - start) / 1e6;
			}
		} catch (InterruptedException e) {
			System.out.println("Hello! We were interrupted.");
		}
		Arrays.sort(durations);
		double median = durations[numTrials / 2];
		if (numTrials % 2 == 0)
			median = (durations[numTrials / 2 - 1] + median) / 2;
		System.out.println(ans + " computed in " + median + "ms (median of " + numTrials
				+ " runs, " + numThreads + " threads)");
		if (ans != expected) {
			System.out.println("WRONG ANSWER: expected " + expected);
			System.exit(2);
		}
	}

}
