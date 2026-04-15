package com.scoreme.scheduler;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Structured output of the scheduler, serialised to JSON for stdout.
 *
 * Fields match the specification from Task 5:
 *   assignment       — task_id → 0-based slot index
 *   penalty          — value of P(σ) for the returned assignment
 *   runtime_ms       — wall-clock ms measured by Main.java
 *   feasible         — true iff a valid assignment satisfying F1/F2/F3 was found
 *   violation_reason — human-readable explanation when feasible == false, else ""
 */
public class SchedulerResult {

    public Map<String, Integer> assignment;
    public double penalty;
    public int    runtimeMs;
    public boolean feasible;
    public String  violationReason;

    /**
     * Serialise to a pretty-printed JSON string compatible with the spec.
     * The key "runtime_ms" (snake_case) matches the required output schema.
     */
    public String toJson() {
        JsonObject obj = new JsonObject();

        // assignment dict
        JsonObject assignObj = new JsonObject();
        if (assignment != null) {
            // Sort by task name for deterministic output
            assignment.entrySet()
                      .stream()
                      .sorted(Map.Entry.comparingByKey())
                      .forEach(e -> assignObj.addProperty(e.getKey(), e.getValue()));
        }
        obj.add("assignment", assignObj);
        obj.addProperty("penalty",          penalty);
        obj.addProperty("runtime_ms",       runtimeMs);
        obj.addProperty("feasible",         feasible);
        obj.addProperty("violation_reason", violationReason != null ? violationReason : "");

        return new GsonBuilder().setPrettyPrinting().create().toJson(obj);
    }
}
