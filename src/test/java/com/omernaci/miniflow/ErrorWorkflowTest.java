package com.omernaci.miniflow;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.execution.Execution;
import com.omernaci.miniflow.execution.ExecutionService;
import com.omernaci.miniflow.execution.ExecutionStore;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ErrorWorkflowTest {

    @Autowired
    ExecutionService executions;

    @Autowired
    ExecutionStore history;

    @Autowired
    WorkflowService store;

    @Autowired
    ObjectMapper mapper;

    private Workflow failing(String id, String errorWorkflow) {
        return new Workflow(id, "Failing " + id, false, List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("pause", "wait", Map.of("seconds", 999))
        ), List.of(new Connection("start", "pause", null)), errorWorkflow);
    }

    private Workflow handler(String id, String errorWorkflow, boolean fails) {
        NodeDef body = fails
                ? new NodeDef("pause", "wait", Map.of("seconds", 999))
                : new NodeDef("capture", "set", Map.of("values", Map.of("handled", true)));
        return new Workflow(id, "Handler " + id, false, List.of(new NodeDef("start", "trigger", Map.of()), body),
                List.of(new Connection("start", body.id(), null)), errorWorkflow);
    }

    @Test
    void failedRunStartsTheErrorWorkflowWithDetails() {
        store.save(handler("ew-handler", null, false));
        Workflow wf = store.save(failing("ew-failing", "ew-handler"));

        Execution failed = executions.run(wf, Map.of("orderId", 42), "webhook");
        assertThat(failed.status()).isEqualTo("ERROR");

        await().atMost(Duration.ofSeconds(10)).until(() -> !history.recentForWorkflow("ew-handler", 5).isEmpty());
        Execution handled = history.recentForWorkflow("ew-handler", 5).get(0);
        assertThat(handled.mode()).isEqualTo(ExecutionService.MODE_ERROR);
        assertThat(handled.status()).isEqualTo("SUCCESS");
        assertThat(handled.trigger())
                .containsEntry("workflowId", "ew-failing")
                .containsEntry("workflowName", "Failing ew-failing")
                .containsEntry("executionId", failed.id())
                .containsEntry("mode", "webhook")
                .containsEntry("failedNode", "pause")
                .containsEntry("trigger", Map.of("orderId", 42));
        assertThat(String.valueOf(handled.trigger().get("error"))).contains("300");
    }

    @Test
    void testRunsAndSuccessfulRunsDoNotStartIt() throws Exception {
        store.save(handler("ew-quiet-handler", null, false));
        Workflow wf = store.save(failing("ew-quiet", "ew-quiet-handler"));

        executions.run(wf, Map.of(), "manual");
        Workflow ok = store.save(new Workflow("ew-ok", "ok", false, List.of(new NodeDef("start", "trigger", Map.of())),
                List.of(), "ew-quiet-handler"));
        executions.run(ok, Map.of(), "schedule");

        Thread.sleep(500);
        assertThat(history.recentForWorkflow("ew-quiet-handler", 5)).isEmpty();
    }

    @Test
    void failingErrorWorkflowDoesNotStartAnother() throws Exception {
        store.save(failing("ew-loop-a", "ew-loop-b"));
        store.save(handler("ew-loop-b", "ew-loop-a", true));

        executions.run(store.find("ew-loop-a").orElseThrow(), Map.of(), "schedule");

        await().atMost(Duration.ofSeconds(10)).until(() -> !history.recentForWorkflow("ew-loop-b", 5).isEmpty());
        Thread.sleep(500);
        assertThat(history.recentForWorkflow("ew-loop-b", 5)).hasSize(1)
                .allMatch(e -> e.status().equals("ERROR") && e.mode().equals(ExecutionService.MODE_ERROR));
        assertThat(history.recentForWorkflow("ew-loop-a", 5)).hasSize(1);
    }

    @Test
    void missingErrorWorkflowIsIgnored() {
        Workflow wf = store.save(failing("ew-missing", "does-not-exist"));
        assertThat(executions.run(wf, Map.of(), "queue").status()).isEqualTo("ERROR");
    }

    @Test
    void invalidErrorWorkflowIsRejected() {
        assertThatThrownBy(() -> store.save(failing("ew-self", "ew-self")))
                .isInstanceOf(MiniFlowException.class).hasMessageContaining("ew-self");
        assertThatThrownBy(() -> store.save(failing("ew-bad", "not valid!")))
                .isInstanceOf(MiniFlowException.class).hasMessageContaining("not valid!");
    }

    @Test
    void errorWorkflowIsKeptThroughImportAndExport() {
        store.importJson("""
                {"id": "ew-roundtrip", "name": "Roundtrip", "errorWorkflow": "ew-handler",
                 "nodes": [{"id": "start", "type": "trigger"}], "connections": []}
                """, true);

        Workflow loaded = store.find("ew-roundtrip").orElseThrow();
        assertThat(loaded.errorWorkflow()).isEqualTo("ew-handler");
        assertThat(loaded.withActive(true).errorWorkflow()).isEqualTo("ew-handler");
        assertThat(mapper.writeValueAsString(loaded)).contains("\"errorWorkflow\"");
        assertThat(mapper.writeValueAsString(store.save(failing("ew-plain", null)))).doesNotContain("errorWorkflow");
    }
}
