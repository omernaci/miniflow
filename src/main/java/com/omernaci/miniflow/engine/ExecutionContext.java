package com.omernaci.miniflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionContext {

    private final String workflowId;
    private final String mode;
    private final Map<String, Object> trigger;
    private final Map<String, Object> nodeOutputs = new LinkedHashMap<>();
    private final List<StepLog> steps = new ArrayList<>();

    public ExecutionContext(String workflowId, String mode, Map<String, Object> trigger) {
        this.workflowId = workflowId;
        this.mode = mode == null ? "manual" : mode;
        this.trigger = trigger == null ? Map.of() : trigger;
    }

    public ExecutionContext(Map<String, Object> trigger) {
        this(null, "manual", trigger);
    }

    public String workflowId() {
        return workflowId;
    }

    public String mode() {
        return mode;
    }

    public boolean isManual() {
        return "manual".equals(mode);
    }

    public Map<String, Object> trigger() {
        return trigger;
    }

    public Map<String, Object> nodeOutputs() {
        return nodeOutputs;
    }

    public List<StepLog> steps() {
        return steps;
    }

    void record(StepLog step) {
        steps.add(step);
        if (step.output() != null) {
            nodeOutputs.put(step.nodeId(), step.output());
        }
    }

    public record StepLog(String nodeId, String type, String status, String branch,
                          Map<String, Object> output, String error, long durationMs, int attempts) {

        public StepLog(String nodeId, String type, String status, String branch,
                       Map<String, Object> output, String error, long durationMs) {
            this(nodeId, type, status, branch, output, error, durationMs, 1);
        }
    }
}
