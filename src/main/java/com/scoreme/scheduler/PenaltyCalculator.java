package com.scoreme.scheduler;

/**
 * Computes the custom penalty function P(σ) = P_base + λ₁·P_imbalance + λ₂·P_sla_risk.
 *
 * ── Design rationale (Task 2) ────────────────────────────────────────────────
 *
 * P_base (given):
 *   P_base(σ) = Σᵢ w(tᵢ) · σ(tᵢ)
 *   Weighted slot index: a high-priority task in a later slot costs more.
 *   This models throughput delay — every extra slot is a ~30-second deferral.
 *
 * P_imbalance (our extension — Load-Imbalance Penalty):
 *   P_imbalance(σ) = Σ_{dim} Σ_s ( util(s,dim) − mean_dim )²
 *   where  util(s,dim) = ( Σ_{i:σ(i)=s} r(i,dim) ) / C(s,dim)
 *          mean_dim    = (1/K) Σ_s util(s,dim)
 *
 *   Motivation: One slot at 95% CPU while another sits at 10% is operationally
 *   undesirable on ScoreMe's shared cluster — it triggers OOM-killer events on
 *   the hot slot and leaves cold-start overhead on the idle one.  The squared
 *   deviation penalises hot-spots superlinearly, so the optimiser actively
 *   spreads load rather than just moving one task.  The term is polynomial
 *   O(K·d) per evaluation and equals 0 only when all dimensions are perfectly
 *   balanced, making it non-trivial and monotonically meaningful.
 *
 * P_sla_risk (our extension — SLA-Breach Risk Penalty):
 *   P_sla_risk(σ) = Σᵢ w(tᵢ) · (σ(tᵢ) − lᵢ) / max(uᵢ − lᵢ, 1)
 *
 *   Motivation: A task placed at slot lᵢ has 0 SLA risk (maximum buffer);
 *   one placed at uᵢ has risk 1 (zero buffer).  Under downstream system
 *   perturbations (NiFi restart, Kafka lag spike), a task at its deadline
 *   cannot be re-scheduled without breaching the SLA.  By penalising deadline
 *   proximity proportional to task weight, we bias the optimiser toward keeping
 *   high-priority tasks well inside their windows.
 *
 * Combined:
 *   P(σ) = P_base + LAMBDA_IMBALANCE · P_imbalance + LAMBDA_SLA_RISK · P_sla_risk
 *
 *   λ₁ = 10.0: imbalance measured in normalised (0-1) utilisation units;
 *               multiplied by 10 to put it on the same order of magnitude as
 *               P_base (which reaches ~10·w·K for high-weight tasks at late slots).
 *   λ₂ =  5.0: risk is already in [0, w·n] so λ₂ = 5 keeps it as a meaningful
 *               but secondary term relative to P_base.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class PenaltyCalculator {

    /** Weight applied to load-imbalance term. */
    public static final double LAMBDA_IMBALANCE = 10.0;

    /** Weight applied to SLA-risk term. */
    public static final double LAMBDA_SLA_RISK = 5.0;

    // ── Full evaluation ──────────────────────────────────────────────────────

    /**
     * Full P(σ) for the given assignment and pre-computed slotUsage matrix.
     * slotUsage[s][dim] = Σ_{i:σ(i)=s} r(i,dim).
     */
    public double compute(Instance inst, int[] assignment, double[][] slotUsage) {
        return computeBase(inst, assignment)
             + LAMBDA_IMBALANCE * computeImbalance(inst, slotUsage)
             + LAMBDA_SLA_RISK  * computeSlaRisk(inst, assignment);
    }

    // ── Individual terms ─────────────────────────────────────────────────────

    /**
     * P_base = Σᵢ w(tᵢ) · σ(tᵢ).
     * Delay cost: assigns a per-unit-of-time cost to each task proportional to
     * its lender SLA importance.
     */
    public double computeBase(Instance inst, int[] assignment) {
        double sum = 0.0;
        for (int i = 0; i < inst.n; i++) {
            sum += inst.weights[i] * assignment[i];
        }
        return sum;
    }

    /**
     * P_imbalance = Σ_{dim} Σ_s ( util(s,dim) − mean_dim )²
     *
     * Each dimension is normalised independently by slot capacity so that a
     * slot at 90% CPU contributes the same deviation as one at 90% RAM.
     * Slots with zero capacity for a dimension are skipped to avoid division
     * by zero (the dimension effectively doesn't exist for that slot).
     */
    public double computeImbalance(Instance inst, double[][] slotUsage) {
        double total = 0.0;
        for (int dim = 0; dim < inst.d; dim++) {
            double meanUtil = 0.0;
            for (int s = 0; s < inst.K; s++) {
                double cap = inst.capacities[s][dim];
                if (cap > 0) {
                    meanUtil += slotUsage[s][dim] / cap;
                }
            }
            meanUtil /= inst.K;

            for (int s = 0; s < inst.K; s++) {
                double cap = inst.capacities[s][dim];
                if (cap > 0) {
                    double diff = (slotUsage[s][dim] / cap) - meanUtil;
                    total += diff * diff;
                }
            }
        }
        return total;
    }

    /**
     * P_sla_risk = Σᵢ w(tᵢ) · (σ(tᵢ) − lᵢ) / max(uᵢ − lᵢ, 1).
     *
     * Normalised position within the SLA window: 0 at earliest allowed slot,
     * 1 at latest allowed slot.  Tasks with a single-slot window contribute
     * 0 regardless of placement (no choice available, hence no "risk").
     */
    public double computeSlaRisk(Instance inst, int[] assignment) {
        double sum = 0.0;
        for (int i = 0; i < inst.n; i++) {
            int windowWidth = Math.max(inst.windows[i][1] - inst.windows[i][0], 1);
            double risk = (double)(assignment[i] - inst.windows[i][0]) / windowWidth;
            sum += inst.weights[i] * risk;
        }
        return sum;
    }

    // ── Incremental (delta) helpers for SA moves ─────────────────────────────

    /**
     * Compute the exact change in P(σ) when task taskIdx is moved from
     * oldSlot to newSlot, without modifying any state.
     *
     * ΔP_base and ΔP_sla_risk are O(1); ΔP_imbalance requires simulating
     * the updated slotUsage and calling computeImbalance twice — O(K·d).
     * For K≤20, d=4 this is 80 multiplications, negligible per SA step.
     */
    public double computeDelta(Instance inst, int taskIdx, int oldSlot, int newSlot,
                               double[][] slotUsage) {
        // ΔP_base
        double deltaBase = inst.weights[taskIdx] * (newSlot - oldSlot);

        // ΔP_sla_risk
        int windowWidth = Math.max(inst.windows[taskIdx][1] - inst.windows[taskIdx][0], 1);
        double deltaSlaRisk = LAMBDA_SLA_RISK
                * inst.weights[taskIdx] * (double)(newSlot - oldSlot) / windowWidth;

        // ΔP_imbalance: simulate the move on a temporary usage copy
        double[][] newUsage = copyUsage(slotUsage, inst.K, inst.d);
        for (int dim = 0; dim < inst.d; dim++) {
            newUsage[oldSlot][dim] -= inst.resources[taskIdx][dim];
            newUsage[newSlot][dim] += inst.resources[taskIdx][dim];
        }
        double deltaImbalance = LAMBDA_IMBALANCE
                * (computeImbalance(inst, newUsage) - computeImbalance(inst, slotUsage));

        return deltaBase + deltaImbalance + deltaSlaRisk;
    }

    /**
     * Compute the marginal cost of adding task taskIdx to slot s for the
     * first time (greedy phase).  slotUsage reflects state *before* placement.
     *
     * Uses the same three-term structure so greedy decisions are consistent
     * with SA refinement — i.e. we are optimising the same objective at both
     * stages.
     */
    public double marginalCost(Instance inst, int taskIdx, int slot,
                               double[][] slotUsage) {
        // Contribution from P_base
        double base = inst.weights[taskIdx] * slot;

        // Contribution from P_sla_risk
        int windowWidth = Math.max(inst.windows[taskIdx][1] - inst.windows[taskIdx][0], 1);
        double slaRisk  = LAMBDA_SLA_RISK * inst.weights[taskIdx]
                        * (double)(slot - inst.windows[taskIdx][0]) / windowWidth;

        // Change in P_imbalance from adding task to slot
        double imbalanceDelta = 0.0;
        for (int dim = 0; dim < inst.d; dim++) {
            double cap = inst.capacities[slot][dim];
            if (cap > 0) {
                double oldUtil = slotUsage[slot][dim] / cap;
                double newUtil = (slotUsage[slot][dim] + inst.resources[taskIdx][dim]) / cap;
                imbalanceDelta += (newUtil * newUtil) - (oldUtil * oldUtil);
            }
        }
        imbalanceDelta *= LAMBDA_IMBALANCE;

        return base + imbalanceDelta + slaRisk;
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /** Deep copy of slotUsage matrix. */
    public static double[][] copyUsage(double[][] usage, int K, int d) {
        double[][] copy = new double[K][d];
        for (int s = 0; s < K; s++) {
            copy[s] = usage[s].clone();
        }
        return copy;
    }

    /** Build slotUsage matrix from a complete assignment array. */
    public static double[][] buildSlotUsage(Instance inst, int[] assignment) {
        double[][] usage = new double[inst.K][inst.d];
        for (int i = 0; i < inst.n; i++) {
            for (int dim = 0; dim < inst.d; dim++) {
                usage[assignment[i]][dim] += inst.resources[i][dim];
            }
        }
        return usage;
    }
}
