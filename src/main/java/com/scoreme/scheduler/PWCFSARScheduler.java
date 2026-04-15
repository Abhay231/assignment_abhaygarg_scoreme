package com.scoreme.scheduler;

import java.util.*;

/**
 * PWCF-SAR: Priority-Weighted Conflict-First (DSATUR) with Simulated Annealing Refinement.
 *
 * ══ Algorithm Overview (Task 3) ══════════════════════════════════════════════
 *
 * This problem combines three NP-hard sub-problems: graph colouring (conflict
 * avoidance F1), d-dimensional vector bin-packing (resource capacity F2), and
 * interval scheduling (SLA windows F3).  No polynomial-time exact algorithm is
 * known unless P = NP.  Our approach is a two-phase heuristic:
 *
 *  PHASE 1 — DYNAMIC DSATUR-STYLE GREEDY
 *  --------------------------------------
 *  Classic DSATUR (Brélaz 1979) chooses the next uncoloured vertex dynamically
 *  by picking the one with the most "saturated" neighbourhood — i.e. the vertex
 *  whose neighbours use the most distinct colours already.  We generalise this:
 *
 *    At each step, select the unplaced task i* that maximises:
 *      saturation(i)  = |{σ(j) : j placed AND j conflicts with i}|
 *                       (number of window-valid slots already blocked by
 *                        placed conflict neighbours of i)
 *    Tie-break order:  1. degree (more conflicts → harder)
 *                      2. 1/window_width (narrower window → fewer options)
 *                      3. weight (higher priority → place earlier)
 *
 *  Dynamic selection adapts to the current partial assignment: once a
 *  high-degree neighbour is placed in a slot, saturation rises and the
 *  affected task jumps to the front.  This avoids the "painting-into-a-corner"
 *  failure mode of static orderings.
 *
 *  For each selected task, we choose the valid slot with minimum marginal P(σ).
 *  If no valid slot exists, a one-level REPAIR step tries to relocate one or
 *  more conflicting tasks out of a window slot to free it.
 *
 *  If repair also fails, we restart the greedy with a randomised perturbation
 *  (up to MAX_GREEDY_RESTARTS attempts) before declaring infeasibility.
 *
 *  PHASE 2 — SIMULATED ANNEALING REFINEMENT
 *  -----------------------------------------
 *  The greedy solution is a feasible seed.  SA explores the feasible region via
 *  single-task reassignment moves that always maintain F1/F2/F3 and accepts
 *  uphill moves with probability exp(−ΔP / T).  T cools geometrically.  The
 *  globally best-seen feasible assignment is tracked and returned.
 *
 * ══ Why DSATUR beats static ordering ════════════════════════════════════════
 *  A static hardness order precomputes task difficulty before any placements.
 *  A task with a narrow window [5,6] and medium degree may have low static
 *  hardness if its weight is low, and get processed last.  By then, both slots
 *  5 and 6 may be occupied by conflicting tasks — a configuration a static
 *  greedy cannot escape.  DSATUR notices the rising saturation of this task as
 *  its neighbours are placed, and promotes it to the front before it becomes
 *  blocked.
 *
 * ══ Rejected alternatives ════════════════════════════════════════════════════
 *  REJECTED: Pure LP relaxation + rounding.
 *    LP gives a fractional lower bound, but rounding back to integers requires
 *    resolving conflicts (a graph-colouring sub-problem) and may violate F2
 *    post-rounding.  The correction phase re-introduces NP-hard complexity.
 *
 *  REJECTED: Tabu search with full neighbourhood.
 *    Full neighbourhood at n=200, K=20 is ~4000 moves/iteration.  SA's
 *    stochastic acceptance provides comparable diversification at ~10× lower
 *    evaluation cost.
 */
public class PWCFSARScheduler {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** SA iterations per task — total = ITERS_PER_TASK * n. */
    private static final int    SA_ITERS_PER_TASK   = 800;
    /** Initial temperature as a fraction of the greedy-solution penalty. */
    private static final double SA_INIT_TEMP_FRAC   = 0.08;
    /** Multiplicative cooling rate per iteration. */
    private static final double SA_COOLING_RATE     = 0.9975;
    /** Floor temperature — stop when T falls below this. */
    private static final double SA_MIN_TEMP         = 1e-9;
    /** Minimum starting temperature (guard for near-zero penalties). */
    private static final double SA_FLOOR_TEMP       = 0.01;
    /**
     * Number of greedy restart attempts when DSATUR fails.
     * Restart 0 is deterministic DSATUR.
     * Restarts 1..MAX-1 use noise-perturbed scores to vary the tie-breaking.
     */
    private static final int    MAX_GREEDY_RESTARTS = 8;

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Instance          inst;
    private final PenaltyCalculator pc;
    private final Random            rng;

    public PWCFSARScheduler(Instance inst) {
        this.inst = inst;
        this.pc   = new PenaltyCalculator();
        this.rng  = new Random(42L);
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run PWCF-SAR and return a SchedulerResult.
     * runtimeMs is populated by Main.java after this call returns.
     */
    public SchedulerResult solve() {

        // ── Phase 1: DSATUR greedy with restarts ─────────────────────────────

        int[]      assignment = null;
        double[][] slotUsage  = null;
        SchedulerResult lastFailure = null;

        for (int attempt = 0; attempt < MAX_GREEDY_RESTARTS; attempt++) {
            double noiseSeed = (attempt == 0) ? 0.0 : attempt * 0.1;
            int[]      asgn  = new int[inst.n];
            double[][] usage = new double[inst.K][inst.d];
            Arrays.fill(asgn, -1);

            GreedyResult gr = runDSATURGreedy(asgn, usage, noiseSeed);
            if (gr.success) {
                assignment = asgn;
                slotUsage  = usage;
                break;
            }
            lastFailure = infeasibleResult(gr.failedTask, asgn, usage);
        }

        if (assignment == null) {
            return lastFailure;
        }

        // ── Phase 2: Simulated annealing refinement ──────────────────────────

        double currentPenalty = pc.compute(inst, assignment, slotUsage);
        double bestPenalty    = currentPenalty;
        int[]  bestAssignment = assignment.clone();

        double T       = Math.max(currentPenalty * SA_INIT_TEMP_FRAC, SA_FLOOR_TEMP);
        int    maxIter = SA_ITERS_PER_TASK * inst.n;

        for (int iter = 0; iter < maxIter && T > SA_MIN_TEMP; iter++) {

            int taskIdx = rng.nextInt(inst.n);
            int oldSlot = assignment[taskIdx];

            List<Integer> valid = validAlternativeSlots(taskIdx, oldSlot, assignment, slotUsage);
            if (valid.isEmpty()) continue;

            int    newSlot = valid.get(rng.nextInt(valid.size()));
            double delta   = pc.computeDelta(inst, taskIdx, oldSlot, newSlot, slotUsage);

            if (delta < 0.0 || rng.nextDouble() < Math.exp(-delta / T)) {
                applyMove(taskIdx, oldSlot, newSlot, assignment, slotUsage);
                currentPenalty += delta;

                if (currentPenalty < bestPenalty) {
                    bestPenalty    = currentPenalty;
                    bestAssignment = assignment.clone();
                }
            }
            T *= SA_COOLING_RATE;
        }

        // ── Build result ─────────────────────────────────────────────────────

        SchedulerResult result = new SchedulerResult();
        result.feasible   = true;
        result.penalty    = bestPenalty;
        result.assignment = new LinkedHashMap<>();
        for (int i = 0; i < inst.n; i++) {
            result.assignment.put(inst.tasks[i], bestAssignment[i]);
        }
        result.violationReason = null;
        return result;
    }

    // ── DSATUR greedy ─────────────────────────────────────────────────────────

    /** Lightweight result carrier for the greedy phase. */
    private static class GreedyResult {
        final boolean success;
        final int     failedTask;
        GreedyResult(boolean s, int t) { success = s; failedTask = t; }
    }

    /**
     * Run one DSATUR greedy pass.
     *
     * Selection criterion at each step: pick the unplaced task i* with the
     * highest saturation, where saturation = number of distinct slots among
     * i's already-placed conflict neighbours that lie within i's SLA window.
     * We count only window-relevant blocked slots because a blocked slot outside
     * the window costs nothing — i can never use that slot anyway.
     *
     * Tie-breaking: degree ↓, window_width ↑ (narrower first), weight ↓.
     *
     * noiseSeed > 0 adds scaled random jitter to break ties differently on
     * restart attempts (deterministic given the seed value).
     *
     * @param assignment  pre-cleared int array (filled with −1)
     * @param slotUsage   pre-zeroed double[K][d] resource usage matrix
     * @param noiseSeed   0 for deterministic; >0 for perturbed tie-breaking
     */
    @SuppressWarnings("unchecked")
    private GreedyResult runDSATURGreedy(int[] assignment, double[][] slotUsage,
                                         double noiseSeed) {
        int n = inst.n;

        // blockedInWindow[i] = set of slots in window(i) already used by a
        // placed conflict neighbour of i.
        Set<Integer>[] blockedInWindow = new Set[n];
        for (int i = 0; i < n; i++) blockedInWindow[i] = new HashSet<>(inst.K);

        boolean[] placed = new boolean[n];
        Random jitter = (noiseSeed > 0) ? new Random((long)(noiseSeed * 999983)) : null;

        for (int step = 0; step < n; step++) {

            // ── Select next task (DSATUR criterion) ──────────────────────────

            int    best     = -1;
            int    bestSat  = -1;
            int    bestDeg  = -1;
            int    bestWin  = Integer.MAX_VALUE;  // lower is better (narrower)
            double bestW    = -1.0;
            double bestNoise = Double.MAX_VALUE;

            for (int i = 0; i < n; i++) {
                if (placed[i]) continue;
                int sat     = blockedInWindow[i].size();
                int deg     = inst.adjacency[i].size();
                int winW    = inst.windows[i][1] - inst.windows[i][0] + 1;
                double w    = inst.weights[i];
                double noise = (jitter != null) ? jitter.nextDouble() * noiseSeed : 0.0;

                boolean better = false;
                if (best == -1)                      better = true;
                else if (sat >  bestSat)             better = true;
                else if (sat == bestSat && deg >  bestDeg)  better = true;
                else if (sat == bestSat && deg == bestDeg && winW < bestWin) better = true;
                else if (sat == bestSat && deg == bestDeg && winW == bestWin
                         && w > bestW)               better = true;
                else if (sat == bestSat && deg == bestDeg && winW == bestWin
                         && w == bestW && noise < bestNoise) better = true;

                if (better) {
                    best = i; bestSat = sat; bestDeg = deg;
                    bestWin = winW; bestW = w; bestNoise = noise;
                }
            }

            // ── Place the selected task ───────────────────────────────────────

            boolean placed_ = tryPlace(best, assignment, slotUsage);
            if (!placed_) placed_ = repairAndPlace(best, assignment, slotUsage);
            if (!placed_) {
                return new GreedyResult(false, best);
            }

            placed[best] = true;

            // ── Update saturation of unplaced conflict neighbours ─────────────

            int assignedSlot = assignment[best];
            for (int nb : inst.adjacency[best]) {
                if (!placed[nb]) {
                    // Only count this slot if it falls within nb's window
                    if (assignedSlot >= inst.windows[nb][0]
                            && assignedSlot <= inst.windows[nb][1]) {
                        blockedInWindow[nb].add(assignedSlot);
                    }
                }
            }
        }

        return new GreedyResult(true, -1);
    }

    // ── Placement helpers ─────────────────────────────────────────────────────

    /**
     * Try to assign task idx to the best valid slot (minimum marginal cost).
     * A slot is valid if it satisfies F1 (no conflict), F2 (capacity), F3 (window).
     *
     * The marginal cost uses the full P(σ) objective — same function as SA —
     * so greedy and SA are optimising the same objective from the start.
     */
    private boolean tryPlace(int idx, int[] assignment, double[][] slotUsage) {
        int    bestSlot = -1;
        double bestCost = Double.MAX_VALUE;

        for (int s = inst.windows[idx][0]; s <= inst.windows[idx][1]; s++) {
            if (!conflictFree(idx, s, assignment)) continue;
            if (!hasCapacity(s, idx, slotUsage))   continue;

            double cost = pc.marginalCost(inst, idx, s, slotUsage);
            if (cost < bestCost) {
                bestCost = cost;
                bestSlot = s;
            }
        }

        if (bestSlot == -1) return false;
        doPlace(idx, bestSlot, assignment, slotUsage);
        return true;
    }

    /**
     * Repair: when task idx cannot be directly placed, try to relocate
     * conflicting tasks out of one of idx's window slots.
     *
     * For each candidate slot s in window(idx):
     *  1. Identify all already-placed tasks j in slot s that conflict with idx.
     *  2. For each j, find an alternative slot s2 in window(j) that is
     *     conflict-free and has capacity — checked in a temporary state.
     *  3. If ALL blockers can be relocated AND slot s has capacity for idx
     *     after the proposed moves: commit all moves and place idx.
     *
     * No priority guard: any blocker may be relocated regardless of weight.
     * The SA phase can subsequently move a displaced high-priority task to a
     * better slot if the displacement increased its penalty.
     */
    private boolean repairAndPlace(int idx, int[] assignment, double[][] slotUsage) {
        for (int s = inst.windows[idx][0]; s <= inst.windows[idx][1]; s++) {

            // Collect tasks in s that conflict with idx
            List<Integer> blockers = new ArrayList<>();
            for (int j = 0; j < inst.n; j++) {
                if (assignment[j] == s && inst.adjacency[idx].contains(j)) {
                    blockers.add(j);
                }
            }

            // Work on copies for atomic commit / rollback
            int[]      tmp  = assignment.clone();
            double[][] uTmp = PenaltyCalculator.copyUsage(slotUsage, inst.K, inst.d);
            Map<Integer, Integer> moves   = new LinkedHashMap<>();
            boolean               allMoved = true;

            for (int j : blockers) {
                boolean moved = false;
                for (int s2 = inst.windows[j][0]; s2 <= inst.windows[j][1]; s2++) {
                    if (s2 == s) continue;
                    if (!conflictFree(j, s2, tmp)) continue;
                    if (!hasCapacity(s2, j, uTmp))  continue;

                    for (int dim = 0; dim < inst.d; dim++) {
                        uTmp[s][dim]  -= inst.resources[j][dim];
                        uTmp[s2][dim] += inst.resources[j][dim];
                    }
                    tmp[j] = s2;
                    moves.put(j, s2);
                    moved = true;
                    break;
                }
                if (!moved) { allMoved = false; break; }
            }

            if (!allMoved) continue;

            // Verify idx can now fit in slot s
            if (!conflictFree(idx, s, tmp)) continue;
            if (!hasCapacity(s, idx, uTmp))  continue;

            // Commit all moves
            for (Map.Entry<Integer, Integer> e : moves.entrySet()) {
                int j = e.getKey(), s2 = e.getValue();
                for (int dim = 0; dim < inst.d; dim++) {
                    slotUsage[assignment[j]][dim] -= inst.resources[j][dim];
                    slotUsage[s2][dim]            += inst.resources[j][dim];
                }
                assignment[j] = s2;
            }
            doPlace(idx, s, assignment, slotUsage);
            return true;
        }
        return false;
    }

    // ── SA helpers ────────────────────────────────────────────────────────────

    /**
     * Enumerate all slots s′ ≠ currentSlot where task taskIdx can be placed
     * while maintaining F1 (no conflict), F2 (residual capacity), F3 (window).
     */
    private List<Integer> validAlternativeSlots(int taskIdx, int currentSlot,
                                                int[] assignment, double[][] slotUsage) {
        List<Integer> valid = new ArrayList<>(inst.K);
        for (int s = inst.windows[taskIdx][0]; s <= inst.windows[taskIdx][1]; s++) {
            if (s == currentSlot) continue;
            // F1
            boolean blocked = false;
            for (int nb : inst.adjacency[taskIdx]) {
                if (assignment[nb] == s) { blocked = true; break; }
            }
            if (blocked) continue;
            // F2 (task removed from currentSlot — its share is still in slotUsage[currentSlot]
            // but that doesn't affect capacity in target slot s)
            boolean fits = true;
            for (int dim = 0; dim < inst.d; dim++) {
                if (slotUsage[s][dim] + inst.resources[taskIdx][dim]
                        > inst.capacities[s][dim] + 1e-9) {
                    fits = false; break;
                }
            }
            if (fits) valid.add(s);
        }
        return valid;
    }

    /** Apply an SA move: update assignment and slotUsage in-place. */
    private void applyMove(int taskIdx, int oldSlot, int newSlot,
                           int[] assignment, double[][] slotUsage) {
        assignment[taskIdx] = newSlot;
        for (int dim = 0; dim < inst.d; dim++) {
            slotUsage[oldSlot][dim] -= inst.resources[taskIdx][dim];
            slotUsage[newSlot][dim] += inst.resources[taskIdx][dim];
        }
    }

    // ── Constraint checkers ───────────────────────────────────────────────────

    /**
     * F1: returns true iff no conflict neighbour of task idx is in slot s.
     */
    boolean conflictFree(int idx, int s, int[] assignment) {
        for (int nb : inst.adjacency[idx]) {
            if (assignment[nb] == s) return false;
        }
        return true;
    }

    /**
     * F2: returns true iff slot s has enough residual capacity for task idx
     * across all d resource dimensions (with floating-point epsilon 1e-9).
     */
    boolean hasCapacity(int s, int idx, double[][] slotUsage) {
        for (int dim = 0; dim < inst.d; dim++) {
            if (slotUsage[s][dim] + inst.resources[idx][dim]
                    > inst.capacities[s][dim] + 1e-9) {
                return false;
            }
        }
        return true;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Place task in slot — must have been validated by caller. */
    private void doPlace(int idx, int s, int[] assignment, double[][] slotUsage) {
        assignment[idx] = s;
        for (int dim = 0; dim < inst.d; dim++) {
            slotUsage[s][dim] += inst.resources[idx][dim];
        }
    }

    /** Build infeasibility result with diagnostic message. */
    private SchedulerResult infeasibleResult(int idx, int[] assignment,
                                             double[][] slotUsage) {
        boolean f1 = false, f2 = false;
        for (int s = inst.windows[idx][0]; s <= inst.windows[idx][1]; s++) {
            if (!conflictFree(idx, s, assignment))  f1 = true;
            else if (!hasCapacity(s, idx, slotUsage)) f2 = true;
        }

        String reason;
        if (f1 && f2) {
            reason = "Task " + inst.tasks[idx]
                    + ": all slots in window [" + inst.windows[idx][0]
                    + "," + inst.windows[idx][1]
                    + "] blocked by both conflict (F1) and capacity (F2) constraints";
        } else if (f1) {
            reason = "Task " + inst.tasks[idx]
                    + ": all slots in window [" + inst.windows[idx][0]
                    + "," + inst.windows[idx][1]
                    + "] blocked by conflict constraints (F1) — chromatic number > K";
        } else {
            reason = "Task " + inst.tasks[idx]
                    + ": all slots in window [" + inst.windows[idx][0]
                    + "," + inst.windows[idx][1]
                    + "] exceed resource capacity (F2)";
        }

        SchedulerResult r = new SchedulerResult();
        r.feasible        = false;
        r.penalty         = Double.MAX_VALUE;
        r.assignment      = null;
        r.violationReason = reason;
        return r;
    }
}
