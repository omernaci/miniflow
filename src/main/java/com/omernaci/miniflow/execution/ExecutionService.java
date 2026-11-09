package com.omernaci.miniflow.execution;

import com.omernaci.miniflow.config.MiniFlowProperties;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.WorkflowService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ExecutionService {

    /** Mode of executions started by an error workflow; they never trigger another error workflow. */
    public static final String MODE_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final WorkflowEngine engine;
    private final ExecutionStore store;
    private final ObjectProvider<WorkflowService> workflows;
    private final MiniFlowProperties props;
    private final ExecutorService errorHandlers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "miniflow-error-workflow");
        t.setDaemon(true);
        return t;
    });

    public ExecutionService(WorkflowEngine engine, ExecutionStore store, ObjectProvider<WorkflowService> workflows,
                            MiniFlowProperties props) {
        this.engine = engine;
        this.store = store;
        this.workflows = workflows;
        this.props = props;
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
        if (failed) {
            errorWorkflowFor(wf, mode).ifPresent(handler ->
                    errorHandlers.execute(() -> runErrorWorkflow(handler, execution)));
        }
        return execution;
    }

    /**
     * The workflow to run when {@code wf} fails: its own {@code errorWorkflow} or {@code miniflow.error-workflow}.
     * Manual (test) runs and runs of an error workflow never start one.
     */
    Optional<Workflow> errorWorkflowFor(Workflow wf, String mode) {
        if ("manual".equals(mode) || MODE_ERROR.equals(mode)) {
            return Optional.empty();
        }
        String handlerId = wf.errorWorkflow() != null ? wf.errorWorkflow() : props.errorWorkflow();
        if (handlerId == null || handlerId.isBlank() || handlerId.equals(wf.id())) {
            return Optional.empty();
        }
        Optional<Workflow> handler = workflows.getObject().find(handlerId.trim());
        if (handler.isEmpty()) {
            log.warn("Error workflow '{}' of workflow '{}' does not exist", handlerId, wf.id());
        }
        return handler;
    }

    private void runErrorWorkflow(Workflow handler, Execution failed) {
        try {
            Execution result = run(handler, errorData(failed), MODE_ERROR);
            if ("ERROR".equals(result.status())) {
                log.warn("Error workflow '{}' failed for execution {}", handler.id(), failed.id());
            }
        } catch (RuntimeException e) {
            log.error("Error workflow '{}' could not be run for execution {}", handler.id(), failed.id(), e);
        }
    }

    static Map<String, Object> errorData(Execution failed) {
        StepLog step = failed.steps().stream().filter(StepLog::failed).reduce((a, b) -> b).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflowId", failed.workflowId());
        data.put("workflowName", failed.workflowName());
        data.put("executionId", failed.id());
        data.put("mode", failed.mode());
        data.put("startedAt", failed.startedAt().toString());
        data.put("durationMs", failed.durationMs());
        data.put("failedNode", step == null ? null : step.nodeId());
        data.put("error", step == null ? null : step.error());
        data.put("trigger", failed.trigger());
        return data;
    }

    @PreDestroy
    void shutdown() {
        errorHandlers.shutdown();
    }
}
