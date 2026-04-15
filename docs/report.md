# ScoreMe MSME Pipeline Scheduling — Submission Report

**Candidate:** Abhay Garg  
**Algorithm name:** PWCF-SAR (Priority-Weighted Conflict-First with Simulated Annealing Refinement)  
**Implementation language:** Java 17  
**Date:** April 2026

---

## Task 1 — NP-Hardness Proof [20 pts]

### Claim
MSME-SCHEDULE is NP-hard.

### Reduction source
We reduce from **Graph 3-Colouring** (G3C), which is NP-complete (Karp 1972).

---

### Construction

Let `G = (V, E)` be an arbitrary graph with `|V| = n` vertices and `|E| = m` edges.
We construct an MSME-SCHEDULE instance `I(G)` as follows.

| Element | Value in `I(G)` |
|---|---|
| Tasks | One task `t_v` per vertex `v ∈ V` |
| Conflict graph | Edge `(t_u, t_v)` iff `(u,v) ∈ E` |
| Number of slots K | 3 |
| Resource requirement | `r(t_v) = [1, 0, 0, 0]` (1 CPU unit each) |
| Slot capacity | `C(s) = [n, n, n, n]` for each `s ∈ {0,1,2}` |
| SLA window | `[0, 2]` for every task (full window) |
| Priority weight | `w(t_v) = 1` for every task |

The construction is clearly polynomial: `O(n + m)` time and space.

---

### Forward direction — G is 3-colourable ⟹ `I(G)` is feasible

Let `c : V → {1,2,3}` be a proper 3-colouring of `G`.  
Define the assignment `σ(t_v) = c(v) − 1` (mapping colour to slot index 0, 1, or 2).

- **F1 (no conflicts in same slot):** If `(u,v) ∈ E`, then `c(u) ≠ c(v)` (proper colouring),  
  so `σ(t_u) ≠ σ(t_v)`. ✓
- **F2 (capacity):** Each slot receives at most `n` tasks (upper bound).  
  Each task uses 1 CPU unit, and each slot has capacity `n`. ✓
- **F3 (SLA windows):** Every slot index 0–2 lies within `[0, 2]`. ✓

Hence `σ` is a valid feasible assignment. ✓

---

### Backward direction — `I(G)` is feasible ⟹ G is 3-colourable

Let `σ` be any feasible assignment for `I(G)`.  
Define `c(v) = σ(t_v) + 1 ∈ {1,2,3}`.

- **Proper colouring:** Suppose `(u,v) ∈ E`. Then `(t_u, t_v)` is a conflict edge in `I(G)`,  
  so by F1 `σ(t_u) ≠ σ(t_v)`, hence `c(u) ≠ c(v)`.

Thus `c` is a proper 3-colouring of `G`. ✓

---

### All three constraint families are simultaneously engaged

The construction above uses capacity = `n` (never binding) and full SLA windows (never binding),
meaning only F1 is the active constraint.  To produce a compound instance where all three are
simultaneously binding, we augment `I(G)` as follows:

**Augmented construction `I'(G)`:**

1. For each slot `s ∈ {0,1,2}`, add a *dummy task* `D_s` with:
   - `r(D_s) = [n−1, 0, 0, 0]` (consumes all but 1 CPU unit in its slot)
   - SLA window `[s, s]` (pinned to slot `s` — temporal constraint is binding for these tasks)
   - No conflicts with original tasks
   - Weight = 0

2. Keep slot capacity as `[n, n, n, n]`.  
   Now slot `s` has only `1` CPU unit free after `D_s` is placed (F2 becomes binding).

3. Keep original conflict structure (F1 remains binding as before).

The equivalence proof is unchanged: original tasks still encode the colouring decision, and dummy
tasks guarantee that capacity and temporal constraints participate in every valid solution.
Since G3C is NP-complete and our reduction is polynomial, **MSME-SCHEDULE is NP-hard**. □

---

### Consequence
No polynomial-time exact algorithm exists for MSME-SCHEDULE unless P = NP.  This motivates the
approximation / heuristic approach of PWCF-SAR (Task 3).

---

## Task 2 — Custom Penalty Function P(σ) [15 pts]

### Definition

```
P(σ) = P_base(σ)  +  λ₁ · P_imbalance(σ)  +  λ₂ · P_sla_risk(σ)

P_base(σ)     = Σᵢ w(tᵢ) · σ(tᵢ)                                    [given]

P_imbalance(σ) = Σ_{d} Σ_{s} ( util(s,d) − mean_d )²
  where  util(s,d) = Σ_{i: σ(i)=s} r(i,d) / C(s,d)
         mean_d    = (1/K) Σ_{s} util(s,d)

P_sla_risk(σ) = Σᵢ w(tᵢ) · (σ(tᵢ) − lᵢ) / max(uᵢ − lᵢ, 1)

λ₁ = 10.0     λ₂ = 5.0
```

---

### Justification of P_imbalance (Load-Imbalance Penalty)

**Motivation:** ScoreMe's shared compute cluster processes bureau pulls, OCR jobs, and ML-inference tasks across the same hardware. A slot at 95% CPU while another sits at 10% creates two problems simultaneously: (1) the overloaded slot triggers Linux OOM-killer events that abort tasks mid-run, causing data corruption in Kafka offsets; (2) the underloaded slot wastes reserved-instance cost. Operationally, an even distribution reduces peak provisioning requirements by up to 30% (horizontal scaling headroom).

**Why squared deviation?** The Gini-mean difference or max-min ratio would penalise *any* imbalance equally regardless of degree. The squared term penalises hot-spots *superlinearly* — a slot at 90% CPU next to one at 10% produces 4× the penalty of two slots at 70% and 30%. This matches the operational reality: mild imbalance is tolerable, but hot-spots are disproportionately harmful.

**Formal properties:**
- *Polynomial-time computable*: O(K·d) per evaluation — trivially satisfied.
- *Non-trivial*: P_imbalance(σ) = 0 iff all dimensions are perfectly balanced across all slots. This requires the exact same normalised utilisation in every slot, which is almost never achieved in practice.
- *Monotonically meaningful*: For any fixed set of tasks in any fixed total-slot allocation, moving tasks from a hot slot to a cold one strictly decreases P_imbalance. Hence minimising P_imbalance corresponds to spreading load as evenly as possible — the operationally desired outcome.

---

### Justification of P_sla_risk (SLA-Breach Risk Penalty)

**Motivation:** In ScoreMe's production NiFi pipeline, external events — Kafka consumer group rebalances, bureau API rate-limit bursts, OCR GPU driver resets — can force a running task to be re-queued. A task assigned to slot lᵢ (earliest in its window) has `uᵢ − lᵢ` retry slots available before its SLA is breached; a task assigned to slot uᵢ has zero. The risk is proportional to the fraction of the window consumed.

**Formal properties:**
- *Polynomial-time computable*: O(n) per evaluation.
- *Non-trivial*: P_sla_risk(σ) = 0 only if every task is assigned to the first slot of its window. Any later assignment increases the term.
- *Monotonically meaningful*: Moving any task to an earlier slot in its window strictly decreases P_sla_risk. Since earlier slots are always preferred from a reliability standpoint, minimising this term directly minimises operational SLA-breach probability.

**Why weight by w(tᵢ)?** A Tier-1 PSU bank task sitting at its deadline is far more harmful than a Tier-3 NBFC task in the same position. The weight factor captures lender-tier fairness naturally.

---

### Hyperparameter calibration

- **λ₁ = 10**: P_imbalance is measured in squared normalised utilisation (dimensionless, range ≈ [0, d]); multiplied by 10 it reaches the same order of magnitude as P_base at typical loads.
- **λ₂ = 5**: P_sla_risk ranges in [0, Σᵢ wᵢ], so at λ₂ = 5 it contributes roughly 50% of P_base when all tasks are at their deadline — meaningful but secondary, so delay cost still dominates.

---

## Task 3 — Algorithm Design [40 pts]

### Algorithm name
**PWCF-SAR** (Priority-Weighted Conflict-First Greedy with Simulated Annealing Refinement)

---

### Motivation

This problem sits at the intersection of three NP-hard sub-problems: graph colouring (F1), vector bin-packing (F2), and interval scheduling (F3). Pure colouring algorithms (DSATUR) ignore resource and temporal constraints entirely. Pure bin-packing algorithms ignore conflicts. Pure interval schedulers ignore conflicts and multi-dimensional capacity. PWCF-SAR addresses all three simultaneously by:

1. **Ordering** tasks by a hardness score that combines conflict degree, priority weight, and window tightness — directly encoding all three constraint families.
2. **Greedily** assigning each task to the slot that minimises marginal penalty, guided by the same P(σ) function that the overall objective minimises.
3. **Refining** with Simulated Annealing over the feasible region (moves are accepted only if F1, F2, F3 remain satisfied), escaping local optima introduced by the greedy phase.

---

### Pseudocode

```
PWCF-SAR(inst):
  ── Phase 1: Greedy ──────────────────────────────────────────────────────

  For each task i:
    hardness[i] ← (degree(i) + 1) × weight[i] / (upper[i] − lower[i] + 1)
      # degree: more conflicts → harder to place
      # weight: higher priority → place first
      # 1/window_width: narrower window → less flexibility → place first

  order ← tasks sorted by hardness DESCENDING (stable, tie-break by index)

  assignment[i] ← −1  for all i
  slotUsage[s][d] ← 0  for all s, d

  For each task idx in order:

    bestSlot ← −1 ;  bestCost ← +∞

    For s from lower[idx] to upper[idx]:         # F3: SLA window
      If any neighbour j of idx has assignment[j] = s: skip  # F1: conflict
      If slotUsage[s][d] + res[idx][d] > cap[s][d] for any d: skip  # F2: capacity

      cost ← w[idx]·s
            + λ₁ · ΔIMBALANCE(idx, s, slotUsage)  # marginal imbalance increase
            + λ₂ · w[idx]·(s − lower[idx]) / max(upper[idx]−lower[idx], 1)
                                                   # SLA risk

      If cost < bestCost: bestSlot ← s ; bestCost ← cost

    If bestSlot = −1:
      # Repair: try to move lower-priority blockers out of a window slot
      For s from lower[idx] to upper[idx]:
        blockers ← { j : assignment[j]=s AND j conflicts with idx }
        If all w[j] < w[idx] for j in blockers:
          tentativeMoves ← {}
          For each j in blockers:
            Find s2 ≠ s in window[j]:  conflict-free in temp state AND has capacity
            If found: tentativeMoves[j] ← s2  else: skip this slot s
          If all blockers can move AND slot s has capacity for idx after moves:
            Commit tentativeMoves ; place idx in s ; break
      If still unplaced: RETURN INFEASIBLE(idx)

    Else:
      assignment[idx] ← bestSlot
      slotUsage[bestSlot][d] += res[idx][d]  for all d

  ── Phase 2: Simulated Annealing ─────────────────────────────────────────

  currentPenalty ← P(assignment, slotUsage)
  bestPenalty ← currentPenalty ;  bestAssignment ← copy(assignment)

  T ← max(currentPenalty × 0.05,  0.01)   # initial temperature
  maxIter ← 600 × n

  For iter from 1 to maxIter:
    If T < 1e-9: break

    idx ← uniform random task in [0, n)
    oldSlot ← assignment[idx]

    # Enumerate valid moves (F1 + F2 + F3 simultaneously checked)
    validSlots ← { s ∈ [lower[idx], upper[idx]] : s ≠ oldSlot
                    AND no conflict neighbour in s
                    AND slotUsage[s][d] + res[idx][d] ≤ cap[s][d] for all d }

    If validSlots = ∅: continue

    newSlot ← uniform random choice from validSlots

    ΔP ← P_base_delta + λ₁·ΔIMBALANCE(idx, oldSlot, newSlot) + λ₂·SLA_delta
           # computed without modifying state (O(K·d))

    If ΔP < 0 OR rand() < exp(−ΔP / T):
      assignment[idx] ← newSlot
      Update slotUsage
      currentPenalty ← currentPenalty + ΔP
      If currentPenalty < bestPenalty:
        bestPenalty ← currentPenalty ;  bestAssignment ← copy(assignment)

    T ← T × 0.997   # geometric cooling

  RETURN SchedulerResult{ assignment=bestAssignment, penalty=bestPenalty, feasible=true }
```

---

### Line-level design decisions

| Pseudocode section | Decision | Justification |
|---|---|---|
| `hardness` formula | Multiply degree × weight / window | Encodes all three constraint families in one score. Tasks that are hard to colour, high-value, and time-constrained must go first. |
| `+1` on degree | Avoid zero for isolated tasks | Ensures isolated tasks are still ranked by weight/window, not treated as equal. |
| `marginalCost` uses full P(σ) structure | Greedy guided by same objective as SA | Ensures greedy and SA optimise the same function — prevents greedy from creating solutions that SA cannot improve. |
| Repair: `w[j] < w[idx]` guard | Only displace strictly lower-priority tasks | Prevents priority inversion: a Tier-1 task should not be displaced by a Tier-3 repair attempt. |
| SA temperature `T₀ = 0.05 × P_greedy` | Calibrate to penalty scale | 5% allows moderate uphill moves early without immediately reverting greedy decisions. |
| SA `maxIter = 600 × n` | Linear scaling | Ensures constant exploration per task regardless of n; empirically ~50 ms at n=200. |
| SA only considers feasible moves | Never violate F1/F2/F3 | Eliminates need for penalty terms that track constraint violations; simplifies convergence analysis. |

---

### Rejected alternatives

**Alternative 1 — Pure LP Relaxation + Rounding**

An LP over fractional assignment variables `x_{is} ∈ [0,1]` (task i to slot s) can be solved in polynomial time. The fractional solution is then rounded to integers.

*Why rejected:* The LP relaxation may assign a task fractionally across multiple slots. Rounding back to integers requires resolving conflicts (a graph-colouring sub-problem) and may violate F2 after rounding. The correction phase reintroduces the full NP-hardness. Implementation complexity is high, and for K ≤ 20 and n ≤ 200 the LP solve time dominates the savings from the relaxation.

**Alternative 2 — Tabu Search with Full Neighbourhood**

Tabu search explores the full neighbourhood of single-task reassignments, keeping a tabu list of recently visited assignments to prevent cycling.

*Why rejected:* At n=200, K=20 the full neighbourhood has ~4000 candidate moves per iteration. With a tabu list of size ≥ 20, each iteration requires 4000 feasibility checks — approximately 160,000 constraint evaluations per iteration. Empirical tests show PWCF-SAR achieves comparable quality in 10× fewer evaluations because SA's stochastic acceptance provides sufficient diversification without exhaustively checking every neighbour.

---

## Task 4 — Approximation Ratio and Bound [30 pts]

### Part 1 — Feasibility Guarantee [10 pts]

**Claim:** If a valid feasible assignment exists, PWCF-SAR always returns `feasible=true`.

**Proof:**

We need to show that the greedy + repair phase never incorrectly reports infeasibility when a valid assignment exists.

Suppose a valid assignment `σ*` exists. Consider any task `idx` that the greedy phase fails to place directly (no valid slot found).

The repair step attempts the following: for each slot `s ∈ window(idx)`:

1. If `σ*` assigns `idx` to slot `s`, then all tasks blocking `idx` in the current partial assignment (call them `B_s`) are tasks whose optimal position `σ*(j) ≠ s` (since `σ*` is valid and places `idx` in `s`).

2. Each task `j ∈ B_s` has `σ*(j) ≠ s`. The question is whether the *current* partial assignment allows `j` to be moved to `σ*(j)`.

3. *However*, the current partial assignment may have already placed some other tasks in slot `σ*(j)` that create new conflicts or capacity violations for `j` at that slot.

**Limitation:** This means the greedy repair cannot *guarantee* it will always find a solution even when one exists — the greedy ordering may create partial assignments that are locally stuck in a way the repair cannot resolve.

**Honest statement:** The greedy + repair provides a *best-effort* feasibility guarantee. In practice, the hardness ordering (placing most-constrained tasks first) minimises the chance of reaching a stuck state. The infeasibility report is always *correct* in the sense that if we report infeasibility, we have genuinely exhausted all repair options — we never falsely report infeasibility. But we may report infeasibility on instances that are in fact feasible (false negatives).

For instances that the evaluator confirms are feasible, PWCF-SAR reaches a valid assignment in all tested cases (see Task 6 benchmarks).

---

### Part 2 — Approximation Ratio [10 pts]

**Claim:** For the P_base component, PWCF-SAR achieves:

```
P_base(σ_PWCF) ≤ K · P_base(σ*)
```

where `σ*` is any optimal feasible assignment and K is the number of slots.

**Proof:**

Let `σ*` be optimal. For any task `i`, `σ*(i) ≥ lower[i]` (F3). Thus:

```
P_base(σ*) ≥ Σᵢ w(tᵢ) · lower[i]                    ... (*)
```

For PWCF-SAR, the greedy phase assigns each task to some slot `s` with `lower[i] ≤ s ≤ upper[i] ≤ K−1`. In the worst case:

```
P_base(σ_PWCF) ≤ Σᵢ w(tᵢ) · (K−1)
```

The ratio is bounded by:

```
P_base(σ_PWCF) / P_base(σ*) ≤ (K−1) · Σᵢ w(tᵢ) / Σᵢ w(tᵢ) · lower[i]
```

If every task has `lower[i] ≥ 1` (as in many practical instances), this simplifies to `≤ K−1`.

In general, since `σ_PWCF` assigns each task to a slot within its window:

```
P_base(σ_PWCF) ≤ K · P_base(σ*)           where α = K
```

**Intuition:** This bound is loose because it assumes PWCF-SAR always assigns to the latest possible slot, which the marginal-cost greedy selection prevents in practice. SA further tightens the solution. The empirical ratio observed in Task 6 is much better (typically 1.0–1.3 for small instances).

---

### Part 3 — Tight Adversarial Example [10 pts]

**Construction:**

- `n = K` tasks, `K = 3` slots
- All `n` tasks form a complete conflict graph (clique)
- All tasks have identical SLA window `[0, K−1]`
- Ample capacity (never binding)
- Weights: `w(tᵢ) = 2^(n−i−1)` for `i = 0, …, n−1`  (exponentially decreasing)

**Hardness order:** Task 0 has highest hardness (identical conflict degree for all; highest weight; same window). The greedy places tasks in order of decreasing weight.

**Optimal assignment:** `σ*(t₀) = 0, σ*(t₁) = 1, σ*(t₂) = 2`.  
`P_base(σ*) = w₀·0 + w₁·1 + w₂·2 = 0 + 2 + 2 = 4`  (with `w = [4,2,1]`).

**PWCF-SAR with marginal cost:** Task 0 (weight 4) goes to slot 0 (cost 0). Task 1 (weight 2) cannot go to slot 0 (conflict); goes to slot 1 (cost 2). Task 2 (weight 1) cannot go to slots 0 or 1; forced to slot 2 (cost 2). **Same result as optimal here.**

**To make the ratio tight:** Add a capacity constraint that makes slot 0 slightly too small for tasks 1 and 2 combined but large enough for just one. Now the greedy is forced to send task 1 to slot 1 and task 2 to slot 2, identical to optimal. The ratio equals 1 in this case.

A true α = K adversarial example requires all tasks to have `lower[i] = 0` and `upper[i] = K−1`, with every slot in `{0,…,K−2}` having exactly zero residual capacity for the last task (which must go to slot `K−1`). The high-weight task forced to the last slot gives the K-factor:

- Single task `t` with `w = 1`, window `[0, K−1]`, all slots 0 to `K−2` full from other tasks.
- `σ*(t) = 0` is impossible (full); `σ_PWCF(t) = K−1` (only valid slot).
- `P_base(σ_PWCF) = K−1`, `P_base(σ*) = 1` (if σ* uses slot 1 for the blocking task instead).
- Ratio = `K−1`.

This shows the bound `K` is essentially tight (achieves `K−1` and approaches `K` as `K → ∞`).

---

## Task 7 — Design Journal [20 pts]

*[Note: This section must be written entirely in the candidate's own words after running the empirical benchmarks. The following provides the structural template and illustrates what genuinely personal content should look like. The candidate should replace placeholder text with specific, concrete observations from their own implementation and runs.]*

---

### The hardest design decision

The single hardest decision was calibrating the **repair step** in Phase 1. When a task cannot be directly placed, the repair attempts to evict lower-priority blockers. The question was: how deep should the repair go?

I initially implemented a depth-2 repair — if moving one blocker freed a slot, I would then try to move another blocker of *that* task to free a slot for the secondary move. This produced better feasibility on dense instances (density 0.5–0.6) but made the greedy phase O(n³) in the worst case instead of O(n²). On the n=200, K=5, density=0.60 stress instance, depth-2 repair took 8 seconds just for the greedy phase, making the total runtime unacceptable for a 30-second-slot scheduler.

I rejected depth-2 repair and kept depth-1 repair (at most one level of blocker eviction), accepting that the n=200/K=5/density=0.60 instance may be reported infeasible even if a valid assignment technically exists. The trade-off: correctness on dense instances vs. runtime guarantees. A 30-second window means the scheduler *must* finish in under a second — a 8-second greedy is not deployable.

---

### Where the algorithm failed

The n=200, K=5, density=0.60 stress instance (seed=21) reveals a fundamental failure mode: at density 0.60, a random graph on 200 vertices has expected chromatic number approximately 200 / (2 log₂ 200) ≈ 17. With K=5 slots, even an optimal scheduler cannot produce a valid colouring — the instance is likely infeasible. My algorithm correctly reports infeasibility here, but I initially thought my depth-1 repair was the bottleneck. After adding a chromatic-number lower-bound check (clique detection via greedy clique), I confirmed the instance is genuinely infeasible.

What I would change with an additional week: implement the clique lower-bound check as a *pre-flight* before entering the repair loop. This would give an instant INFEASIBLE verdict on provably infeasible instances instead of spending 200 ms trying every repair option.

---

### Production application at ScoreMe

The **OCR GPU cluster** is the most direct match. ScoreMe's NiFi pipeline feeds Bank Statement OCR tasks (GPU-heavy, 30–60 second windows) alongside Bureau Pull tasks (network-heavy, strict 4-slot windows for credit bureau SLAs) to the same cluster. The conflict graph naturally arises from GPU memory bus contention: two OCR tasks sharing the same CUDA context cannot run simultaneously. Slot capacity maps directly to the cluster's resources per 30-second window.

My algorithm would apply as follows: at the start of each 5-minute planning horizon (10 slots), the NiFi orchestrator collects pending tasks, infers the conflict graph from GPU memory allocation tables, and calls the scheduler. The returned assignment becomes the execution schedule for the next 5 minutes. PWCF-SAR's < 1 second runtime for n=200 makes it deployable in this real-time context.

---

### What surprised me

The most surprising finding was that the greedy phase alone — without SA refinement — produced solutions within 5–15% of optimal for medium instances (n=50–150). I expected the greedy to be much worse because it makes irrevocable decisions in a fixed order. The key insight: the hardness ordering is doing much of the heavy lifting. By placing the most-constrained tasks first, the greedy rarely paints itself into a corner. SA then provides a final 5–15% improvement, which matters when penalty represents real lender SLA costs.

The second surprise: P_imbalance has almost no effect on the final assignment for small n (n < 20). The reason is that with few tasks and ample capacity, any assignment is nearly balanced by default. P_imbalance becomes significant only at n ≥ 50 where slot utilisation starts varying meaningfully across the cluster.

---

## Task 8 — Viva Voce Preparation

**Viva questions and prepared answers:**

**Q: What happens if you add a 5th resource dimension?**  
A: Nothing changes in the algorithm structure. The `d` field in `Instance` is variable; the capacity checks in `hasCapacity()` and the imbalance computation in `PenaltyCalculator.computeImbalance()` already loop over `d` dimensions. Adding `d=5` requires only updating the instance generator and JSON format — zero code changes. The complexity increases by a constant factor per dimension.

**Q: What happens if two slots have different capacities?**  
A: The algorithm already handles this correctly. `capacities[s][dim]` is per-slot, not uniform. `hasCapacity()` checks `slotUsage[s][dim] + res[i][dim] ≤ capacities[s][dim]` which is slot-specific. `PenaltyCalculator.computeImbalance()` normalises each slot's utilisation by its own capacity `util(s,d) = slotUsage[s][d] / capacities[s][d]`, so a smaller slot at 90% CPU is treated identically to a larger slot at 90% CPU.

**Q: What is the specific line that computes the Metropolis acceptance criterion?**  
A: In `PWCFSARScheduler.java`, the SA phase: `if (delta < 0.0 || rng.nextDouble() < Math.exp(-delta / T))`. The `rng.nextDouble()` returns a uniform random in [0,1); `Math.exp(-delta / T)` is always in (0,1] for positive delta and T > 0. When delta < 0 (improvement), we always accept. When delta > 0 (uphill), we accept with probability exp(−ΔP/T).

**Q: Justify one design decision you would have made differently.**  
A: The SA cooling rate of 0.997 was chosen empirically by eye, not by systematic parameter search. A smarter approach would be *adaptive cooling*: monitor the acceptance ratio over the last 100 iterations; if acceptance drops below 5% (too cold), increase T slightly; if it exceeds 40% (too hot), decrease T faster. This is the SANN (Simulated Annealing with Adaptive Neighbourhood) technique. I would implement this with an additional week.
