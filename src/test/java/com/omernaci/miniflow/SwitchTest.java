package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.node.NodeTypeInfo;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SwitchTest {

    @Autowired
    WorkflowEngine engine;

    private ExecutionContext route(String category, Map<String, Object> extraParams) {
        Map<String, Object> params = new HashMap<>(Map.of("value", "{{ input.category }}",
                "cases", List.of("billing", "technical", "complaint")));
        params.putAll(extraParams);
        Workflow wf = new Workflow("switch-test", "switch", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("route", "switch", params),
                new NodeDef("billing", "set", Map.of("values", Map.of("team", "finance"))),
                new NodeDef("technical", "set", Map.of("values", Map.of("team", "engineering"))),
                new NodeDef("other", "set", Map.of("values", Map.of("team", "support")))
        ), List.of(new Connection("start", "route", null),
                new Connection("route", "billing", "billing"),
                new Connection("route", "technical", "technical"),
                new Connection("route", "other", "default")));
        Map<String, Object> input = new HashMap<>();
        input.put("category", category);
        return engine.run(wf, input);
    }

    private static String routedTo(ExecutionContext ctx) {
        return ctx.steps().stream().filter(s -> s.nodeId().equals("route")).map(StepLog::branch).findFirst().orElseThrow();
    }

    @Test
    void routesToTheMatchingCase() {
        ExecutionContext ctx = route("technical", Map.of());
        assertThat(routedTo(ctx)).isEqualTo("technical");
        assertThat(ctx.nodeOutputs().get("technical")).isEqualTo(Map.of("category", "technical", "team", "engineering"));
        assertThat(ctx.nodeOutputs()).doesNotContainKeys("billing", "other");
    }

    @Test
    void unmatchedOrMissingValuesGoToDefault() {
        assertThat(routedTo(route("refund", Map.of()))).isEqualTo("default");
        assertThat(routedTo(route(null, Map.of()))).isEqualTo("default");
        ExecutionContext complaint = route("complaint", Map.of());
        assertThat(routedTo(complaint)).isEqualTo("complaint");
        assertThat(complaint.steps()).extracting(StepLog::nodeId).containsExactly("start", "route");
    }

    @Test
    void ignoreCaseIsOptional() {
        assertThat(routedTo(route("BILLING", Map.of()))).isEqualTo("default");
        assertThat(routedTo(route("BILLING", Map.of("ignoreCase", true)))).isEqualTo("billing");
    }

    @Test
    void invalidCasesFail() {
        assertThat(route("x", Map.of("cases", List.of("a", "a"))).steps().get(1).error()).contains("a");
        assertThat(route("x", Map.of("cases", List.of("default"))).steps().get(1).error()).contains("default");
        assertThat(route("x", Map.of("cases", List.of())).steps().get(1).error()).contains("cases");
    }

    @Test
    void exposesCasesAsDynamicOutputs() {
        NodeTypeInfo info = engine.nodeTypes().stream().filter(t -> t.type().equals("switch")).findFirst().orElseThrow();
        assertThat(info.dynamicOutputs()).isEqualTo("cases");
        assertThat(info.outputs()).containsExactly("default");
    }
}
