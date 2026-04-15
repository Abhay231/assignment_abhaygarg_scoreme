package com.scoreme.scheduler;

import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Optimal exhaustive solver for small instances via recursive backtracking.
 *
 * Used as the ground-truth comparator for Task 6 empirical analysis
 * (benchmark instances with n ≤ 14).  Not suitable beyond ~n=14 due to
 * exponential worst-case complexity O(K^n), but in practice constraint
 * propagation prunes the search tree very aggressively:
 *
 *  – F1 (conflict) pruning: once a neighbour is assigned to slot s,
 *    slot s is removed from the current task's candidates immediately.
 *  – F2 (capacity) pruning: once a slot's remaining capacity is less than
 *    the task's requirement, it is skipped.
 *  – Bound pruning: if the partial P_base already exceeds the best known
 *    full penalty, the branch is cut — works well when high-weight tasks
 *    are processed first.
 *
 * Tasks are ordered by decreasing conflict degree (same motivation as
 * PWCF-SAR phase 1) to maximise early pruning.
 */
public class BruteForceScheduler {

    private final Instance         inst;
    private final PenaltyCalculator pc;

    // Best solution found so far
    private int[]  bestAssignment;
    private double bestPenalty;

    public BruteForceScheduler(Instance inst) {
        this.inst = inst;
        this.pc   = new PenaltyCalculator();
    }

    /**
     * Run exhaustive backtracking and return the optimal feasible assignment,
     * or an infeasible result if no valid assignment exists.
     *
     * Tasks are processed in degree-descending order to prune early; the order
     * does not affect correctness, only search speed.
     */
    public SchedulerResult solve() {
        bestAssignment = null;
        bestPenalty    = Double.MAX_VALUE;

        // Order by degree descending for aggressive early pruning
        Integer[] order = new Integer[inst.n];
        for (int i = 0; i < inst.n; i++) order[i] = i;
        Arrays.sort(order, (a, b) ->
                Integer.compare(inst.adjacency[b].size(), inst.adjacency[a].size()));

        int[]      assignment = new int[inst.n];
        double[][] slotUsage  = new double[inst.K][inst.d];
        Arrays.fill(assignment, -1);

        backtrack(0, order, assignment, slotUsage, 0.0);

        SchedulerResult result = new SchedulerResult();
        if (bestAssignment == null) {
            result.feasible        = false;
            result.penalty         = Double.MAX_VALUE;
            result.assignment      = null;
            result.violationReason = "No feasible assignment exists (exhaustive search)";
        } else {
            result.feasible   = true;
            result.penalty    = bestPenalty;
            result.assignment = new LinkedHashMap<>();
            for (int i = 0; i < inst.n; i++) {
                result.assignment.put(inst.tasks[i], bestAssignment[i]);
            }
            result.violationReason = null;
        }
        return result;
    }

    /**
     * Core recursive backtracking function.
     *
     * @param depth          current depth in the order array (0 = first task)
     * @param order          permutation of task indices (sorted by degree desc)
     * @param assignment     partial assignment (−1 = unassigned)
     * @param slotUsage      per-slot resource sums (accumulated incrementally)
     * @param partialBase    Σᵢ w(i)·σ(i) accumulated so far (for bound pruning)
     */
    private void backtrack(int depth, Integer[] order,
                           int[] assignment, double[][] slotUsage,
                           double partialBase) {

        // All tasks assigned — evaluate and update best
        if (depth == inst.n) {
            double penalty = pc.compute(inst, assignment, slotUsage);
            if (penalty < bestPenalty) {
                bestPenalty    = penalty;
                bestAssignment = assignment.clone();
            }
            return;
        }

        int idx = order[depth];

        for (int s = inst.windows[idx][0]; s <= inst.windows[idx][1]; s++) {

            // F1: conflict check — skip if any assigned neighbour is in slot s
            boolean conflict = false;
            for (int nb : inst.adjacency[idx]) {
                if (assignment[nb] == s) { conflict = true; break; }
            }
            if (conflict) continue;

            // F2: capacity check across all four dimensions
            boolean fits = true;
            for (int dim = 0; dim < inst.d; dim++) {
                if (slotUsage[s][dim] + inst.resources[idx][dim]
                        > inst.capacities[s][dim] + 1e-9) {
                    fits = false;
                    break;
                }
            }
            if (!fits) continue;

            // Bound pruning: lower bound on penalty for this branch.
            // We use P_base of already-placed tasks + w[idx]*s as a partial bound.
            // This is a loose lower bound (remaining tasks could get slot 0), but
            // it cuts branches where we've already exceeded the best known penalty.
            double newPartialBase = partialBase + inst.weights[idx] * s;
            if (newPartialBase >= bestPenalty) continue;  // prune

            // Place and recurse
            assignment[idx] = s;
            for (int dim = 0; dim < inst.d; dim++) {
                slotUsage[s][dim] += inst.resources[idx][dim];
            }

            backtrack(depth + 1, order, assignment, slotUsage, newPartialBase);

            // Undo placement
            assignment[idx] = -1;
            for (int dim = 0; dim < inst.d; dim++) {
                slotUsage[s][dim] -= inst.resources[idx][dim];
            }
        }
    }
}
