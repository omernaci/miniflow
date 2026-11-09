package com.omernaci.miniflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionContext {

    private final String workflowId;
    private final String mode;
    private final Map<String, Object> trigger;
    private final Map<String, Object> nodeOutputs = new LinkedHashMap<>();
    private final List<StepLog> steps = new ArrayList<>();
    private final Deque<Map<String, Object>> loops = new ArrayDeque<>();

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

    /** The innermost running loop as { index, number, total, first, last, item }, or {@code null}. */
    public Map<String, Object> loop() {
        return loops.peek();
    }

    void enterLoopItem(int index, int total, Object item) {
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("index", index);
        loop.put("number", index + 1);
        loop.put("total", total);
        loop.put("first", index == 0);
        loop.put("last", index == total - 1);
        loop.put("item", item);
        loops.push(loop);
    }

    void exitLoopItem() {
        loops.pop();
    }

    void record(StepLog step) {
        Map<String, Object> loop = loops.peek();
        if (loop != null && step.iteration() == null) {
            step = step.withIteration((Integer) loop.get("number"));
        }
        steps.add(step);
        if (step.output() != null) {
            nodeOutputs.put(step.nodeId(), step.output());
        }
    }

    public record StepLog(String nodeId, String type, String status, String branch,
                          Map<String, Object> output, String error, long durationMs, int attempts,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Integer iteration) {

        public StepLog(String nodeId, String type, String status, String branch,
                       Map<String, Object> output, String error, long durationMs, int attempts) {
            this(nodeId, type, status, branch, output, error, durationMs, attempts, null);
        }

        public StepLog(String nodeId, String type, String status, String branch,
                       Map<String, Object> output, String error, long durationMs) {
            this(nodeId, type, status, branch, output, error, durationMs, 1, null);
        }

        StepLog withIteration(Integer value) {
            return new StepLog(nodeId, type, status, branch, output, error, durationMs, attempts, value);
        }

        /** An error that was not handled by an error branch, i.e. one that makes the execution fail. */
        public boolean failed() {
            return "ERROR".equals(status) && !"error".equals(branch);
        }
    }
}
