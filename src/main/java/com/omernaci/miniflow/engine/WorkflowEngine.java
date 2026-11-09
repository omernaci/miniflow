package com.omernaci.miniflow.engine;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.common.i18n.Messages;
import com.omernaci.miniflow.engine.expression.ExpressionResolver;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.NodeTypeInfo;
import com.omernaci.miniflow.node.core.LoopNode;
import com.omernaci.miniflow.workflow.RetryPolicy;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_STEPS = 1000;

    private final Map<String, NodeExecutor> executors;
    private final ExpressionResolver expressions;

    public WorkflowEngine(List<NodeExecutor> executorList, ExpressionResolver expressions) {
        this.executors = executorList.stream()
                .collect(Collectors.toMap(NodeExecutor::type, Function.identity()));
        this.expressions = expressions;
        log.info("Registered node types: {}", executors.keySet());
    }

    private static final List<String> PALETTE_ORDER = List.of("trigger", "set", "if", "loop", "aggregate", "dedupe", "wait", "http", "ai",
            "email", "slack", "github", "googleSheets", "database", "spreadsheet", "file", "rss",
            "redis", "rabbitmq", "kafka", "log");

    public List<NodeTypeInfo> nodeTypes() {
        return executors.values().stream()
                .sorted(Comparator.comparingInt((NodeExecutor e) -> {
                    int i = PALETTE_ORDER.indexOf(e.type());
                    return i < 0 ? Integer.MAX_VALUE : i;
                }).thenComparing(NodeExecutor::type))
                .map(NodeTypeInfo::from)
                .toList();
    }

    public ExecutionContext run(Workflow workflow, Map<String, Object> triggerData) {
        return run(workflow, triggerData, "manual");
    }

    public ExecutionContext run(Workflow workflow, Map<String, Object> triggerData, String mode) {
        ExecutionContext ctx = new ExecutionContext(workflow.id(), mode, triggerData);
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(workflow.startNode().id(), ctx.trigger()));
        drain(workflow, queue, ctx, new int[]{0});
        return ctx;
    }

    /**
     * Runs nodes until the queue is empty. Returns the output of the last node that ran (used as the result
     * of a loop iteration), or {@code null} when a node failed without an error branch.
     */
    private Branch drain(Workflow workflow, Deque<Pending> queue, ExecutionContext ctx, int[] stepCount) {
        Map<String, Object> last = null;
        boolean failed = false;
        while (!queue.isEmpty()) {
            if (++stepCount[0] > MAX_STEPS) {
                throw new MiniFlowException("error.engine.maxSteps", MAX_STEPS);
            }
            Pending current = queue.poll();
            NodeDef node = workflow.node(current.nodeId())
                    .orElseThrow(() -> new MiniFlowException("error.engine.nodeNotFound", current.nodeId()));

            boolean errorHandled = node.continuesOnError()
                    && !workflow.nextNodes(node.id(), NodeDef.ERROR_BRANCH).isEmpty();
            NodeResult result = executeNode(node, current.input(), ctx, errorHandled);
            if (result == null) {
                failed = true;
                continue;
            }
            if (LoopNode.TYPE.equals(node.type()) && LoopNode.ITEM.equals(result.branch())) {
                result = runLoop(workflow, node, result, ctx, stepCount);
                if (result == null) {
                    failed = true;
                    continue;
                }
            }
            last = result.output();
            for (String next : workflow.nextNodes(node.id(), result.branch())) {
                queue.add(new Pending(next, result.output()));
            }
        }
        return new Branch(last, failed);
    }

    /** Runs the {@code item} branch once per item and returns the {@code done} result with all item outputs. */
    private NodeResult runLoop(Workflow workflow, NodeDef loop, NodeResult started, ExecutionContext ctx,
                               int[] stepCount) {
        List<?> items = (List<?>) started.output().get("items");
        List<String> body = workflow.nextNodes(loop.id(), LoopNode.ITEM);
        List<Object> results = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            ctx.enterLoopItem(i, items.size(), item);
            Branch iteration;
            try {
                Deque<Pending> queue = new ArrayDeque<>();
                body.forEach(next -> queue.add(new Pending(next, LoopNode.itemInput(item))));
                iteration = drain(workflow, queue, ctx, stepCount);
            } finally {
                ctx.exitLoopItem();
            }
            if (iteration.failed()) {
                String error = Messages.get("error.loop.itemFailed", i + 1, items.size());
                ctx.record(new ExecutionContext.StepLog(loop.id(), loop.type(), "ERROR", null, null, error,
                        System.currentTimeMillis() - start, 1));
                return null;
            }
            results.add(iteration.output() == null ? LoopNode.itemInput(item) : iteration.output());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", results);
        out.put("count", results.size());
        ctx.record(new ExecutionContext.StepLog(loop.id(), loop.type(), "SUCCESS", LoopNode.DONE, out, null,
                System.currentTimeMillis() - start, 1));
        return NodeResult.branch(out, LoopNode.DONE);
    }

    private NodeResult executeNode(NodeDef node, Map<String, Object> input, ExecutionContext ctx,
                                   boolean errorHandled) {
        long start = System.currentTimeMillis();
        NodeExecutor executor = executors.get(node.type());
        if (executor == null) {
            String error = Messages.get("error.engine.unknownNodeType", node.type());
            ctx.record(new ExecutionContext.StepLog(node.id(), node.type(), "ERROR", null, null, error, 0, 1));
            return null;
        }
        RetryPolicy retry = node.retry();
        int maxAttempts = retry == null ? 1 : retry.times() + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                Map<String, Object> params = expressions.resolve(node.params(), input, ctx);
                NodeResult result = executor.execute(params, input, ctx);
                ctx.record(new ExecutionContext.StepLog(node.id(), node.type(), "SUCCESS", result.branch(),
                        result.output(), null, System.currentTimeMillis() - start, attempt));
                return result;
            } catch (Exception e) {
                if (attempt < maxAttempts && sleepBeforeRetry(node, retry, attempt, e)) {
                    continue;
                }
                log.warn("Node '{}' failed after {} attempt(s): {}", node.id(), attempt, e.getMessage());
                if (errorHandled) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", e.getMessage());
                    error.put("nodeId", node.id());
                    error.put("type", node.type());
                    error.put("attempts", attempt);
                    error.put("input", input);
                    ctx.record(new ExecutionContext.StepLog(node.id(), node.type(), "ERROR", NodeDef.ERROR_BRANCH,
                            error, e.getMessage(), System.currentTimeMillis() - start, attempt));
                    return NodeResult.branch(error, NodeDef.ERROR_BRANCH);
                }
                ctx.record(new ExecutionContext.StepLog(node.id(), node.type(), "ERROR", null,
                        null, e.getMessage(), System.currentTimeMillis() - start, attempt));
                return null;
            }
        }
    }

    private static boolean sleepBeforeRetry(NodeDef node, RetryPolicy retry, int attempt, Exception error) {
        long delay = retry.delayBeforeRetry(attempt);
        log.info("Node '{}' failed (attempt {}), retrying in {} ms: {}", node.id(), attempt, delay, error.getMessage());
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Pending(String nodeId, Map<String, Object> input) {
    }

    private record Branch(Map<String, Object> output, boolean failed) {
    }
}
