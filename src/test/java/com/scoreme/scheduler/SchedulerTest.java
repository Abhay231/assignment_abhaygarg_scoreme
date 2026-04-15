package com.scoreme.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Unit tests for the PWCF-SAR scheduler covering all four required edge cases
 * from Task 5 plus additional correctness and regression tests.
 *
 * Required cases (Task 5):
 *  1. All-conflict graph (chromatic number > K)  → infeasible
 *  2. Zero-capacity slot                          → tasks avoid that slot
 *  3. Tight SLA windows                           → pinned assignments
 *  4. Single-task instance                        → trivially feasible
 *
 * Additional cases:
 *  5. Toy instance from Section 3.3              → feasible + verify all constraints
 *  6. Capacity infeasibility (task too large)     → infeasible
 *  7. BruteForce vs PWCF-SAR on same instance    → BF penalty ≤ PWCF penalty
 *  8. PenaltyCalculator correctness              → manual computation check
 */
class SchedulerTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  Required test 1 — All-conflict graph with chromatic number > K
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Required-1: Complete graph K₄ with K=2 slots → infeasible")
    void testAllConflictGraphInfeasible() {
        // 4 tasks forming a complete conflict graph (K₄).
        // K₄ requires 4 colours (slots) to colour.  With only K=2 slots,
        // no valid assignment exists — chromatic number (4) > K (2).
        Instance inst = build(4, 2,
            new int[][]{{0,1},{0,2},{0,3},{1,2},{1,3},{2,3}},   // K₄ edges
            uniform(4, new double[]{1, 2, 0, 0}),               // tasks: 1 CPU, 2 RAM each
            uniform(2, new double[]{10, 20, 10, 10}),           // ample capacity
            allWindow(4, 0, 1),                                  // window [0,1]
            ones(4)
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();

        assertFalse(result.feasible,
            "A complete K₄ conflict graph with K=2 slots must be infeasible");
        assertNotNull(result.violationReason);
        assertFalse(result.violationReason.isEmpty(), "violation_reason must be non-empty");
    }

    @Test
    @DisplayName("Required-1b: BruteForce also detects K₄ infeasibility")
    void testAllConflictBruteForceInfeasible() {
        Instance inst = build(4, 2,
            new int[][]{{0,1},{0,2},{0,3},{1,2},{1,3},{2,3}},
            uniform(4, new double[]{1, 2, 0, 0}),
            uniform(2, new double[]{10, 20, 10, 10}),
            allWindow(4, 0, 1),
            ones(4)
        );

        SchedulerResult result = new BruteForceScheduler(inst).solve();
        assertFalse(result.feasible, "BruteForce: K₄ with K=2 must be infeasible");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Required test 2 — Zero-capacity slot
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Required-2: Slot 0 has zero CPU capacity — all tasks must land in slot 1")
    void testZeroCapacitySlot() {
        // K=2: slot 0 has zero capacity on every dimension → no task can go there.
        // Both tasks have window [0,1] so they must all be assigned to slot 1.
        Instance inst = build(2, 2,
            new int[][]{},                                         // no conflicts
            uniform(2, new double[]{1, 2, 0, 0}),
            new double[][]{{0, 0, 0, 0}, {10, 20, 10, 10}},      // slot 0: zero, slot 1: large
            allWindow(2, 0, 1),
            ones(2)
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();

        assertTrue(result.feasible, "Should find feasible assignment using slot 1");
        for (Map.Entry<String, Integer> e : result.assignment.entrySet()) {
            assertEquals(1, (int) e.getValue(),
                "Task " + e.getKey() + " must be in slot 1 (slot 0 has zero capacity)");
        }
        verifyFeasibility(inst, result);
    }

    @Test
    @DisplayName("Required-2b: Zero-capacity slot is also infeasible if only slot 0 exists")
    void testZeroCapacityOnlySlot() {
        // Single slot with zero capacity — not even 1 task can be placed.
        Instance inst = build(1, 1,
            new int[][]{},
            new double[][]{{5, 10, 0, 0}},                       // task needs resources
            new double[][]{{0, 0, 0, 0}},                        // slot has zero cap
            new int[][]{{0, 0}},
            new double[]{1.0}
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();
        assertFalse(result.feasible, "Task into zero-capacity only slot must be infeasible");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Required test 3 — Tight SLA windows
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Required-3: Each task pinned to a distinct single-slot window")
    void testTightSlaWindowsPinned() {
        // K=3, 3 tasks each with a single-slot window [s,s].
        // The only valid assignment is T0→0, T1→1, T2→2.
        Instance inst = build(3, 3,
            new int[][]{},                                        // no conflicts
            uniform(3, new double[]{1, 1, 0, 0}),
            uniform(3, new double[]{5, 5, 5, 5}),
            new int[][]{{0,0},{1,1},{2,2}},                      // tight windows
            ones(3)
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();

        assertTrue(result.feasible);
        assertEquals(0, (int) result.assignment.get("T0"), "T0 pinned to slot 0");
        assertEquals(1, (int) result.assignment.get("T1"), "T1 pinned to slot 1");
        assertEquals(2, (int) result.assignment.get("T2"), "T2 pinned to slot 2");
        verifyFeasibility(inst, result);
    }

    @Test
    @DisplayName("Required-3b: Tight window infeasible when conflict prevents only option")
    void testTightSlaWindowConflictInfeasible() {
        // T0 and T1 both pinned to slot 0 AND conflict with each other → infeasible.
        Instance inst = build(2, 2,
            new int[][]{{0, 1}},                                 // T0 conflicts T1
            uniform(2, new double[]{1, 1, 0, 0}),
            uniform(2, new double[]{5, 5, 5, 5}),
            new int[][]{{0, 0}, {0, 0}},                        // both pinned to slot 0
            ones(2)
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();
        assertFalse(result.feasible,
            "Conflicting tasks pinned to same slot must be infeasible");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Required test 4 — Single-task instance
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Required-4: Single task must be placed in its SLA window")
    void testSingleTask() {
        // One task with window [1,2] — must land in slot 1 or 2.
        Instance inst = build(1, 3,
            new int[][]{},
            new double[][]{{2, 4, 1, 0.5}},
            uniform(3, new double[]{10, 20, 5, 5}),
            new int[][]{{1, 2}},
            new double[]{5.0}
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();

        assertTrue(result.feasible, "Single task should always be placed");
        assertEquals(1, result.assignment.size());
        int slot = result.assignment.get("T0");
        assertTrue(slot >= 1 && slot <= 2, "Slot " + slot + " not in window [1,2]");
        verifyFeasibility(inst, result);
    }

    @Test
    @DisplayName("Required-4b: Single task into impossible window (all capacity exceeded)")
    void testSingleTaskCapacityInfeasible() {
        // Task needs 100 CPU but every slot has only 10 CPU.
        Instance inst = build(1, 2,
            new int[][]{},
            new double[][]{{100, 1, 0, 0}},
            uniform(2, new double[]{10, 100, 100, 100}),
            new int[][]{{0, 1}},
            new double[]{1.0}
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();
        assertFalse(result.feasible, "Task exceeding all capacities must be infeasible");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Additional test 5 — Toy instance from Section 3.3
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Extra-5: Toy instance from Section 3.3 is feasible and constraint-valid")
    void testToyInstance() {
        Instance inst = new Instance();
        inst.n = 6;  inst.K = 4;  inst.d = 4;
        inst.tasks     = new String[]{"T1","T2","T3","T4","T5","T6"};
        inst.conflicts = new int[][]{{0,1},{0,2},{1,3},{2,4},{3,5},{4,5}};
        inst.resources = new double[][]{
            {8,32,4,1.5},{4,16,0,3.0},{2,8,0,2.0},
            {16,64,2,0.5},{8,32,2,1.0},{4,16,0,1.5}
        };
        inst.capacities = new double[][]{ // 4 uniform slots
            {32,128,8,6.0},{32,128,8,6.0},{32,128,8,6.0},{32,128,8,6.0}
        };
        // Problem uses 1-indexed, generator uses 0-indexed: subtract 1
        inst.windows = new int[][]{{0,2},{0,3},{0,3},{1,3},{0,3},{1,3}};
        inst.weights = new double[]{5,4,3,2,3,2};
        inst.buildAdjacency();

        SchedulerResult result = new PWCFSARScheduler(inst).solve();

        assertTrue(result.feasible, "Toy instance must be feasible");
        assertNotNull(result.assignment);
        verifyFeasibility(inst, result);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Additional test 6 — BruteForce ≤ PWCF-SAR penalty on same instance
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Extra-6: BruteForce penalty ≤ PWCF-SAR penalty (BF is optimal)")
    void testBruteForceOptimalityVsHeuristic() {
        // 6-task instance — small enough for BF, large enough to be non-trivial
        Instance inst = build(6, 3,
            new int[][]{{0,1},{1,2},{2,3},{3,4},{4,5},{0,3}},  // sparse conflict graph
            uniform(6, new double[]{3, 6, 1, 0.5}),
            uniform(3, new double[]{15, 36, 6, 3}),
            new int[][]{{0,2},{0,2},{0,2},{0,2},{0,2},{0,2}},
            new double[]{5,4,3,2,5,4}
        );

        SchedulerResult bf   = new BruteForceScheduler(inst).solve();
        SchedulerResult pwcf = new PWCFSARScheduler(inst).solve();

        // Both must be feasible for comparison
        assertTrue(bf.feasible,   "BF must find feasible solution");
        assertTrue(pwcf.feasible, "PWCF-SAR must find feasible solution");

        // BF is optimal — its penalty must be ≤ heuristic penalty
        assertTrue(bf.penalty <= pwcf.penalty + 1e-6,
            String.format("BF penalty %.4f > PWCF penalty %.4f — BF must be optimal",
                          bf.penalty, pwcf.penalty));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Additional test 7 — PenaltyCalculator manual check
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Extra-7: PenaltyCalculator P_base matches hand-computed value")
    void testPenaltyCalculatorBase() {
        // 3 tasks, weights [2, 3, 1], assigned to slots [0, 1, 2]
        // P_base = 2*0 + 3*1 + 1*2 = 5
        Instance inst = build(3, 3,
            new int[][]{},
            uniform(3, new double[]{1, 1, 0, 0}),
            uniform(3, new double[]{5, 5, 5, 5}),
            new int[][]{{0,2},{0,2},{0,2}},
            new double[]{2.0, 3.0, 1.0}
        );

        int[] assignment = {0, 1, 2};
        PenaltyCalculator pc = new PenaltyCalculator();

        double base = pc.computeBase(inst, assignment);
        assertEquals(5.0, base, 1e-9, "P_base must equal 2*0 + 3*1 + 1*2 = 5");
    }

    @Test
    @DisplayName("Extra-7b: P_imbalance is 0 when all slots have equal utilisation")
    void testPenaltyImbalanceZeroWhenBalanced() {
        // 2 slots, 2 tasks each using exactly 50% of CPU in their slot → imbalance = 0
        Instance inst = build(2, 2,
            new int[][]{{0, 1}},  // must go to different slots
            new double[][]{{5, 0, 0, 0}, {5, 0, 0, 0}},
            uniform(2, new double[]{10, 10, 10, 10}),
            new int[][]{{0,1},{0,1}},
            ones(2)
        );

        // Force T0→slot0, T1→slot1
        double[][] usage = new double[2][4];
        usage[0][0] = 5.0;   // slot 0: 50% CPU
        usage[1][0] = 5.0;   // slot 1: 50% CPU

        PenaltyCalculator pc = new PenaltyCalculator();
        double imbalance = pc.computeImbalance(inst, usage);
        assertEquals(0.0, imbalance, 1e-9, "Equal utilisation must give imbalance = 0");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Additional test 8 — Repair mechanism
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Extra-8: Repair succeeds when lower-priority blocker can be relocated")
    void testRepairMechanism() {
        // T0 (weight=10, window=[0,1]) and T1 (weight=1, window=[0,1]) conflict.
        // T2 (weight=10, window=[0,1]) conflicts with T1 but not T0.
        // Greedy places T0→0, T1→0-blocked→1; T2 can go to 0 or 1.
        // More importantly: verify the scheduler finds a feasible solution.
        Instance inst = build(3, 2,
            new int[][]{{0,1},{1,2}},                     // T0-T1, T1-T2
            uniform(3, new double[]{1, 1, 0, 0}),
            uniform(2, new double[]{5, 5, 5, 5}),
            allWindow(3, 0, 1),
            new double[]{10, 1, 10}                       // T0 and T2 are high priority
        );

        SchedulerResult result = new PWCFSARScheduler(inst).solve();
        assertTrue(result.feasible, "Should find feasible placement via repair or direct");
        verifyFeasibility(inst, result);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verify that result satisfies F1, F2, F3 for the given instance.
     * Fails the test with a descriptive message on any violation.
     */
    private void verifyFeasibility(Instance inst, SchedulerResult result) {
        // Build int[] assignment from task-id map
        int[] asgn = new int[inst.n];
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < inst.n; i++) idx.put(inst.tasks[i], i);
        for (Map.Entry<String, Integer> e : result.assignment.entrySet()) {
            asgn[idx.get(e.getKey())] = e.getValue();
        }

        // F1: no two conflicting tasks in same slot
        for (int[] edge : inst.conflicts) {
            assertNotEquals(asgn[edge[0]], asgn[edge[1]],
                String.format("F1 violated: tasks %s and %s both in slot %d",
                              inst.tasks[edge[0]], inst.tasks[edge[1]], asgn[edge[0]]));
        }

        // F2: no slot exceeds capacity
        double[][] usage = new double[inst.K][inst.d];
        for (int i = 0; i < inst.n; i++) {
            for (int dim = 0; dim < inst.d; dim++) {
                usage[asgn[i]][dim] += inst.resources[i][dim];
            }
        }
        for (int s = 0; s < inst.K; s++) {
            for (int dim = 0; dim < inst.d; dim++) {
                assertTrue(usage[s][dim] <= inst.capacities[s][dim] + 1e-9,
                    String.format("F2 violated: slot %d dim %d usage %.2f > cap %.2f",
                                  s, dim, usage[s][dim], inst.capacities[s][dim]));
            }
        }

        // F3: all assignments within SLA windows
        for (int i = 0; i < inst.n; i++) {
            int lo = inst.windows[i][0], hi = inst.windows[i][1];
            assertTrue(asgn[i] >= lo && asgn[i] <= hi,
                String.format("F3 violated: task %s assigned to slot %d, window [%d,%d]",
                              inst.tasks[i], asgn[i], lo, hi));
        }
    }

    // ── Builder helpers ──────────────────────────────────────────────────────

    /** Build a simple Instance from primitive arrays. */
    private Instance build(int n, int K, int[][] conflicts,
                           double[][] resources, double[][] capacities,
                           int[][] windows, double[] weights) {
        Instance inst   = new Instance();
        inst.n          = n;
        inst.K          = K;
        inst.d          = 4;
        inst.tasks      = new String[n];
        for (int i = 0; i < n; i++) inst.tasks[i] = "T" + i;
        inst.conflicts  = conflicts;
        inst.resources  = resources;
        inst.capacities = capacities;
        inst.windows    = windows;
        inst.weights    = weights;
        inst.buildAdjacency();
        return inst;
    }

    /**
     * Build an n-row 2-D array where every row is a clone of {@code vec}.
     * Used for both resources (n tasks) and capacities (K slots).
     */
    private double[][] uniform(int n, double[] vec) {
        double[][] r = new double[n][vec.length];
        for (int i = 0; i < n; i++) r[i] = vec.clone();
        return r;
    }

    /** n tasks all with window [lo, hi]. */
    private int[][] allWindow(int n, int lo, int hi) {
        int[][] w = new int[n][2];
        for (int i = 0; i < n; i++) { w[i][0] = lo; w[i][1] = hi; }
        return w;
    }

    /** n tasks all with weight 1.0. */
    private double[] ones(int n) {
        double[] w = new double[n];
        Arrays.fill(w, 1.0);
        return w;
    }
}
