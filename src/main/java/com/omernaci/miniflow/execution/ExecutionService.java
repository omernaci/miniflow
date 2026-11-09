package com.omernaci.miniflow.execution;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.workflow.Workflow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExecutionService {

    private final WorkflowEngine engine;
    private final ExecutionStore store;

    public ExecutionService(WorkflowEngine engine, ExecutionStore store) {
        this.engine = engine;
        this.store = store;
    }

    public Execution run(Workflow wf, Map<String, Object> data, String mode) {
        Map<String, Object> trigger = data == null ? Map.of() : data;
        LocalDateTime startedAt = LocalDateTime.now();
        long t0 = System.nanoTime();

        List<StepLog> steps;
        try {
            ExecutionContext ctx = engine.run(wf, trigger, mode);
            steps = List.copyOf(ctx.steps());
        } catch (Exception e) {
            steps = List.of(new StepLog("-", "engine", "ERROR", null, null, e.getMessage(), 0));
        }

        boolean failed = steps.stream().anyMatch(StepLog::failed);
        Execution execution = new Execution(UUID.randomUUID().toString(), wf.id(), wf.name(), mode,
                failed ? "ERROR" : "SUCCESS", startedAt, (System.nanoTime() - t0) / 1_000_000,
                trigger, steps);
        store.add(execution);
        return execution;
    }
}
