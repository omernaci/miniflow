package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.execution.Execution;
import com.omernaci.miniflow.execution.ExecutionService;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoopTest {

    @Autowired
    ExecutionService executions;

    private static NodeDef set(String id, Map<String, Object> values) {
        return new NodeDef(id, "set", Map.of("keepInput", false, "values", values));
    }

    private static Workflow workflow(List<NodeDef> nodes, List<Connection> connections) {
        return new Workflow("loop-test", "loop test", nodes, connections);
    }

    private static Connection conn(String from, String to, String branch) {
        return new Connection(from, to, branch);
    }

    private static StepLog last(Execution ex, String nodeId) {
        return ex.steps().stream().filter(s -> s.nodeId().equals(nodeId)).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void runsTheItemBranchOncePerItemAndCollectsResults() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.rows }}")),
                set("greet", Map.of("greeting", "Hi {{ input.name }} ({{ loop.number }}/{{ loop.total }})",
                        "first", "{{ loop.first }}")),
                set("summary", Map.of("count", "{{ input.count }}", "greetings", "{{ input.items.![greeting] }}"))
        ), List.of(conn("start", "each", null), conn("each", "greet", "item"), conn("each", "summary", "done")));

        Execution ex = executions.run(wf, Map.of("rows", List.of(
                Map.of("name", "Ada"), Map.of("name", "Linus"), Map.of("name", "Grace"))), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(last(ex, "summary").output()).containsEntry("count", 3)
                .containsEntry("greetings", List.of("Hi Ada (1/3)", "Hi Linus (2/3)", "Hi Grace (3/3)"));
        assertThat(ex.steps().stream().filter(s -> s.nodeId().equals("greet")).map(StepLog::iteration))
                .containsExactly(1, 2, 3);
        assertThat(ex.steps().stream().filter(s -> s.nodeId().equals("greet")).map(s -> s.output().get("first")))
                .containsExactly(true, false, false);
        assertThat(last(ex, "each").branch()).isEqualTo("done");
        assertThat(last(ex, "summary").iteration()).isNull();
    }

    @Test
    void wrapsNonMapItemsAsValue() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.numbers }}")),
                set("double", Map.of("result", "{{ input.value * 2 }}"))
        ), List.of(conn("start", "each", null), conn("each", "double", "item")));

        Execution ex = executions.run(wf, Map.of("numbers", List.of(1, 2, 3)), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(last(ex, "each").output()).containsEntry("items",
                List.of(Map.of("result", 2), Map.of("result", 4), Map.of("result", 6)));
    }

    @Test
    void emptyListGoesStraightToDone() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.rows }}")),
                set("body", Map.of("x", "1")),
                set("after", Map.of("count", "{{ input.count }}"))
        ), List.of(conn("start", "each", null), conn("each", "body", "item"), conn("each", "after", "done")));

        Execution ex = executions.run(wf, Map.of("rows", List.of()), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(ex.steps()).noneMatch(s -> s.nodeId().equals("body"));
        assertThat(last(ex, "after").output()).containsEntry("count", 0);
    }

    @Test
    void rejectsTooManyItemsAndNonLists() {
        Workflow tooMany = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.rows }}", "maxItems", 2))
        ), List.of(conn("start", "each", null)));
        Execution ex = executions.run(tooMany, Map.of("rows", List.of(1, 2, 3)), "manual");
        assertThat(ex.status()).isEqualTo("ERROR");
        assertThat(last(ex, "each").error()).contains("3").contains("2");

        Workflow notList = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.name }}"))
        ), List.of(conn("start", "each", null)));
        assertThat(executions.run(notList, Map.of("name", "x"), "manual").status()).isEqualTo("ERROR");
    }

    @Test
    void failingItemStopsTheLoop() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.waits }}")),
                new NodeDef("pause", "wait", Map.of("seconds", "{{ input.value }}")),
                set("after", Map.of("x", "1"))
        ), List.of(conn("start", "each", null), conn("each", "pause", "item"), conn("each", "after", "done")));

        Execution ex = executions.run(wf, Map.of("waits", List.of(0, 999, 0)), "manual");

        assertThat(ex.status()).isEqualTo("ERROR");
        assertThat(ex.steps().stream().filter(s -> s.nodeId().equals("pause")).count()).isEqualTo(2);
        assertThat(last(ex, "each").error()).contains("2").contains("3");
        assertThat(ex.steps()).noneMatch(s -> s.nodeId().equals("after"));
    }

    @Test
    void errorBranchInsideTheBodyKeepsTheLoopGoing() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.waits }}")),
                new NodeDef("pause", "wait", Map.of("seconds", "{{ input.value }}"), null, null, null, "branch"),
                set("ok", Map.of("status", "ok")),
                set("failed", Map.of("status", "failed"))
        ), List.of(conn("start", "each", null), conn("each", "pause", "item"),
                conn("pause", "ok", null), conn("pause", "failed", "error")));

        Execution ex = executions.run(wf, Map.of("waits", List.of(0, 999, 0)), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        assertThat(last(ex, "each").output()).containsEntry("items",
                List.of(Map.of("status", "ok"), Map.of("status", "failed"), Map.of("status", "ok")));
    }

    @Test
    void loopsCanBeNested() {
        Workflow wf = workflow(List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("outer", "loop", Map.of("items", "{{ input.groups }}")),
                new NodeDef("inner", "loop", Map.of("items", "{{ input.value }}")),
                set("cell", Map.of("v", "{{ input.value }}", "pos", "{{ loop.number }}/{{ loop.total }}"))
        ), List.of(conn("start", "outer", null), conn("outer", "inner", "item"), conn("inner", "cell", "item")));

        Execution ex = executions.run(wf, Map.of("groups", List.of(List.of(1, 2), List.of(3))), "manual");

        assertThat(ex.status()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outer = (List<Map<String, Object>>) last(ex, "outer").output().get("items");
        assertThat(outer).extracting(m -> m.get("count")).containsExactly(2, 1);
        assertThat(ex.steps().stream().filter(s -> s.nodeId().equals("cell")).map(s -> s.output().get("pos")))
                .containsExactly("1/2", "2/2", "1/1");
    }
}
