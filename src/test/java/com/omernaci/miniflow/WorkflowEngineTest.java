package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
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
class WorkflowEngineTest {

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowService store;

    @BeforeEach
    void orderFlow() {
        TestWorkflows.ensureOrderFlow(store);
    }

    @Test
    void bigOrderGoesToTrueBranch() {
        Workflow wf = store.find("order-flow").orElseThrow();
        ExecutionContext ctx = engine.run(wf, Map.of("orderId", 42, "customer", "Ayşe", "amount", 1500));

        List<String> executed = ctx.steps().stream().map(ExecutionContext.StepLog::nodeId).toList();
        assertThat(executed).containsExactly("start", "calc", "check", "big", "done");
        assertThat(ctx.steps()).allMatch(s -> s.status().equals("SUCCESS"));
        assertThat(ctx.nodeOutputs().get("done")).isEqualTo(Map.of("orderId", 42, "priority", "HIGH"));
    }

    @Test
    void smallOrderGoesToFalseBranch() {
        Workflow wf = store.find("order-flow").orElseThrow();
        ExecutionContext ctx = engine.run(wf, Map.of("orderId", 7, "customer", "Mehmet", "amount", 100));

        List<String> executed = ctx.steps().stream().map(ExecutionContext.StepLog::nodeId).toList();
        assertThat(executed).containsExactly("start", "calc", "check", "small", "done");
        @SuppressWarnings("unchecked")
        Map<String, Object> small = (Map<String, Object>) ctx.nodeOutputs().get("small");
        assertThat(small.get("logged")).isEqualTo("Regular order: Order #7 - Mehmet, total 120.0");
    }

    @Test
    void unsafeExpressionsAreBlocked() {
        Workflow wf = new Workflow("evil", "evil", List.of(
                new Workflow.NodeDef("start", "trigger", Map.of()),
                new Workflow.NodeDef("x", "set", Map.of("values",
                        Map.of("v", "{{ T(java.lang.Runtime).getRuntime().exec('id') }}")))
        ), List.of(new Workflow.Connection("start", "x", null)));

        ExecutionContext ctx = engine.run(wf, Map.of());
        assertThat(ctx.steps().get(1).status()).isEqualTo("ERROR");
    }
}
