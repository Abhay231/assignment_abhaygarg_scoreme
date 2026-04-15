package com.scoreme.scheduler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable data model for one MSME Credit Pipeline Scheduling instance.
 *
 * Populated from JSON produced by the reference generator (Section 5 of assignment).
 * All slot and task indices are 0-based to match the generator output.
 *
 * Field layout mirrors the formal specification Section 3.2:
 *   n, K, d    — problem dimensions
 *   tasks      — task name strings ("T0", "T1", …)
 *   conflicts  — edge list of conflicting task-index pairs
 *   resources  — resources[i][dim] ∈ ℝ for task i, dimension dim
 *   capacities — capacities[s][dim] for slot s
 *   windows    — windows[i] = {lower, upper} (0-indexed, inclusive)
 *   weights    — priority weight w(tᵢ)
 *   adjacency  — adjacency[i] = set of task indices conflicting with i (derived)
 */
public class Instance {

    public int     n;            // number of tasks
    public int     K;            // number of slots
    public int     d = 4;        // resource dimensions (CPU, RAM, GPU, Net)
    public String[] tasks;       // task names
    public int[][] conflicts;    // edge list; each entry is [i, j]
    public double[][] resources;  // resources[task][dim]
    public double[][] capacities; // capacities[slot][dim]
    public int[][] windows;       // windows[task] = {lo, hi}
    public double[] weights;      // priority weights

    /** Derived adjacency sets for O(degree) conflict lookup. */
    @SuppressWarnings("unchecked")
    public Set<Integer>[] adjacency;

    /**
     * Build adjacency[i] from the flat conflict edge list.
     * Called once after deserialization; result is used throughout the algorithm.
     * Kept separate so tests can construct instances without JSON.
     */
    @SuppressWarnings("unchecked")
    public void buildAdjacency() {
        adjacency = new Set[n];
        for (int i = 0; i < n; i++) {
            adjacency[i] = new HashSet<>();
        }
        for (int[] edge : conflicts) {
            adjacency[edge[0]].add(edge[1]);
            adjacency[edge[1]].add(edge[0]);
        }
    }

    /**
     * Parse a JSON string produced by the reference generator into an Instance.
     * Expected keys: tasks, conflicts, resources, capacities, windows, weights, K.
     * Robustly handles both integer and floating-point resource/capacity values.
     */
    public static Instance fromJson(String json) {
        Gson gson = new Gson();
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        Instance inst = new Instance();
        inst.K = obj.get("K").getAsInt();

        // ---- tasks ----
        JsonArray tasksArr = obj.getAsJsonArray("tasks");
        inst.n = tasksArr.size();
        inst.tasks = new String[inst.n];
        for (int i = 0; i < inst.n; i++) {
            inst.tasks[i] = tasksArr.get(i).getAsString();
        }

        // ---- conflicts ----
        JsonArray conflictsArr = obj.getAsJsonArray("conflicts");
        inst.conflicts = new int[conflictsArr.size()][2];
        for (int i = 0; i < conflictsArr.size(); i++) {
            JsonArray edge = conflictsArr.get(i).getAsJsonArray();
            inst.conflicts[i][0] = edge.get(0).getAsInt();
            inst.conflicts[i][1] = edge.get(1).getAsInt();
        }

        // ---- resources ----
        JsonArray resArr = obj.getAsJsonArray("resources");
        inst.resources = new double[inst.n][inst.d];
        for (int i = 0; i < inst.n; i++) {
            JsonArray row = resArr.get(i).getAsJsonArray();
            for (int dim = 0; dim < inst.d; dim++) {
                inst.resources[i][dim] = row.get(dim).getAsDouble();
            }
        }

        // ---- capacities ----
        JsonArray capArr = obj.getAsJsonArray("capacities");
        inst.capacities = new double[inst.K][inst.d];
        for (int s = 0; s < inst.K; s++) {
            JsonArray row = capArr.get(s).getAsJsonArray();
            for (int dim = 0; dim < inst.d; dim++) {
                inst.capacities[s][dim] = row.get(dim).getAsDouble();
            }
        }

        // ---- windows ----
        JsonArray winArr = obj.getAsJsonArray("windows");
        inst.windows = new int[inst.n][2];
        for (int i = 0; i < inst.n; i++) {
            JsonArray w = winArr.get(i).getAsJsonArray();
            inst.windows[i][0] = w.get(0).getAsInt();
            inst.windows[i][1] = w.get(1).getAsInt();
        }

        // ---- weights ----
        JsonArray wtsArr = obj.getAsJsonArray("weights");
        inst.weights = new double[inst.n];
        for (int i = 0; i < inst.n; i++) {
            inst.weights[i] = wtsArr.get(i).getAsDouble();
        }

        inst.buildAdjacency();
        return inst;
    }
}
