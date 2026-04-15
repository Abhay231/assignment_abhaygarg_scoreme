package com.scoreme.scheduler;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the PWCF-SAR scheduler.
 *
 * Usage:
 *   java -jar scheduler.jar --input <instance.json> [--brute-force]
 *   java -jar scheduler.jar [--brute-force]          # reads JSON from stdin
 *
 * Flags:
 *   --input <file>   Path to a JSON instance file (omit to read from stdin).
 *   --brute-force    Force the exhaustive BruteForceScheduler regardless of n.
 *                    Automatically used for n ≤ BRUTE_FORCE_THRESHOLD.
 *
 * Output: a single JSON object on stdout with keys:
 *   assignment       (object: task_id → slot index)
 *   penalty          (number)
 *   runtime_ms       (integer)
 *   feasible         (boolean)
 *   violation_reason (string; empty when feasible)
 */
public class Main {

    /**
     * Instances with n at or below this threshold are solved to optimality by
     * BruteForceScheduler.  14 is chosen because BF with tight pruning solves
     * n=12, K=4 instances in under 200 ms in empirical tests.
     */
    private static final int BRUTE_FORCE_THRESHOLD = 14;

    public static void main(String[] args) throws Exception {
        String  inputFile       = null;
        boolean forceBruteForce = false;
        boolean forceHeuristic  = false;   // force PWCF-SAR even for small n

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                    if (i + 1 < args.length) inputFile = args[++i];
                    break;
                case "--brute-force":
                    forceBruteForce = true;
                    break;
                case "--heuristic":
                    forceHeuristic = true;
                    break;
                default:
                    if (!args[i].startsWith("--")) inputFile = args[i];
            }
        }

        // Read JSON from file or stdin
        String json = (inputFile != null)
                ? Files.readString(Path.of(inputFile))
                : new String(System.in.readAllBytes());

        Instance inst = Instance.fromJson(json);

        long start = System.currentTimeMillis();

        SchedulerResult result;
        if (!forceHeuristic && (forceBruteForce || inst.n <= BRUTE_FORCE_THRESHOLD)) {
            result = new BruteForceScheduler(inst).solve();
        } else {
            result = new PWCFSARScheduler(inst).solve();
        }

        result.runtimeMs = (int)(System.currentTimeMillis() - start);

        System.out.println(result.toJson());
    }
}
