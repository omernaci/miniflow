package com.omernaci.miniflow;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.execution.Execution;
import com.omernaci.miniflow.execution.ExecutionService;
import com.omernaci.miniflow.workflow.RetryPolicy;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ErrorBranchTest {

    @Autowired
    ExecutionService executions;

    @Autowired
    WorkflowService store;

    @Autowired
    ObjectMapper mapper;

    private Workflow workflow(String onError, RetryPolicy retry, boolean connectErrorBranch) {
        List<Connection> connections = new ArrayList<>(List.of(
                new Connection("start", "fail", null),
                new Connection("fail", "after", null)));
        if (connectErrorBranch) {
            connections.add(new Connection("fail", "handle", NodeDef.ERROR_BRANCH));
        }
        return new Workflow("error-branch", "error branch", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("fail", "wait", Map.of("seconds", 999), null, null, retry, onError),
                new NodeDef("after", "log", Map.of("message", "not reached")),
                new NodeDef("handle", "set", Map.of("keepInput", false, "values", Map.of(
                        "failedNode", "{{ input.nodeId }}",
                        "message", "{{ input.error }}",
                        "attempts", "{{ input.attempts }}",
                        "original", "{{ input.input.orderId }}")))
        ), connections);
    }

    private StepLog step(Execution ex, String nodeId) {
        return ex.steps().stream().filter(s -> s.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void failureContinuesOnErrorBranchAndExecutionSucceeds() {
        Execution ex = executions.run(workflow("branch", null, true), Map.of("orderId", 7), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(ex.steps()).extracting(StepLog::nodeId).containsExactly("start", "fail", "handle");
        StepLog failed = step(ex, "fail");
        assertThat(failed.status()).isEqualTo("ERROR");
        assertThat(failed.branch()).isEqualTo("error");
        assertThat(step(ex, "handle").output())
                .containsEntry("failedNode", "fail")
                .containsEntry("attempts", 1)
                .containsEntry("original", 7);
        assertThat(String.valueOf(step(ex, "handle").output().get("message"))).contains("300");
    }

    @Test
    void retryRunsBeforeTheErrorBranch() {
        Execution ex = executions.run(workflow("branch", new RetryPolicy(2, 1L, 1.0), true), Map.of(), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(step(ex, "fail").attempts()).isEqualTo(3);
        assertThat(step(ex, "handle").output()).containsEntry("attempts", 3);
    }

    @Test
    void withoutConnectionTheWorkflowStillFails() {
        Execution ex = executions.run(workflow("branch", null, false), Map.of(), "manual");

        assertThat(ex.status()).isEqualTo("ERROR");
        assertThat(step(ex, "fail").branch()).isNull();
        assertThat(ex.steps()).extracting(StepLog::nodeId).containsExactly("start", "fail");
    }

    @Test
    void defaultStopsTheWorkflow() {
        Execution ex = executions.run(workflow(null, null, true), Map.of(), "manual");

        assertThat(ex.status()).isEqualTo("ERROR");
        assertThat(ex.steps()).extracting(StepLog::nodeId).containsExactly("start", "fail");
    }

    @Test
    void invalidOnErrorIsRejected() {
        Workflow wf = new Workflow("error-branch-invalid", "invalid", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("fail", "log", Map.of("message", "x"), null, null, null, "ignore")
        ), List.of(new Connection("start", "fail", null)));

        assertThatThrownBy(() -> store.save(wf))
                .isInstanceOf(MiniFlowException.class)
                .hasMessageContaining("ignore");
    }

    @Test
    void onErrorIsKeptThroughImportAndExport() {
        String json = """
                {"id": "error-branch-roundtrip", "name": "Error branch roundtrip", "nodes": [
                  {"id": "start", "type": "trigger"},
                  {"id": "call", "type": "log", "params": {"message": "hi"}, "onError": "branch"},
                  {"id": "handle", "type": "log", "params": {"message": "failed"}}
                ], "connections": [{"from": "start", "to": "call"}, {"from": "call", "to": "handle", "branch": "error"}]}
                """;
        store.importJson(json, true);

        Workflow loaded = store.find("error-branch-roundtrip").orElseThrow();
        assertThat(loaded.node("call").orElseThrow().continuesOnError()).isTrue();
        assertThat(loaded.node("handle").orElseThrow().onError()).isNull();
        assertThat(loaded.nextNodes("call", "error")).containsExactly("handle");

        String exported = mapper.writeValueAsString(loaded);
        assertThat(exported.indexOf("\"onError\"")).isEqualTo(exported.lastIndexOf("\"onError\"")).isPositive();
        store.delete("error-branch-roundtrip");
    }
}
