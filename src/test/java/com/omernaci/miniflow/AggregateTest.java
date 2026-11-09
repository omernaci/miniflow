package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
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
class AggregateTest {

    static final List<Map<String, Object>> ORDERS = List.of(
            Map.of("id", 1, "total", 120, "customer", Map.of("country", "TR")),
            Map.of("id", 2, "total", "80.5", "customer", Map.of("country", "DE")),
            Map.of("id", 3, "total", 40, "customer", Map.of("country", "TR")));

    @Autowired
    WorkflowEngine engine;

    private StepLog aggregate(Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        p.putIfAbsent("items", "{{ input.orders }}");
        Workflow wf = new Workflow("aggregate-test", "aggregate", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("agg", "aggregate", p)
        ), List.of(new Connection("start", "agg", null)));
        ExecutionContext ctx = engine.run(wf, Map.of("orders", ORDERS));
        return ctx.steps().get(1);
    }

    private Object result(Map<String, Object> params) {
        StepLog step = aggregate(params);
        assertThat(step.error()).isNull();
        return step.output().get("result");
    }

    @Test
    void collectsCountsAndJoins() {
        assertThat(result(Map.of("mode", "collect", "field", "id"))).isEqualTo(List.of(1, 2, 3));
        assertThat(result(Map.of("mode", "collect", "field", "customer.country"))).isEqualTo(List.of("TR", "DE", "TR"));
        assertThat(result(Map.of("mode", "count"))).isEqualTo(3);
        assertThat(result(Map.of("mode", "join", "field", "id", "separator", " | "))).isEqualTo("1 | 2 | 3");
        assertThat(result(Map.of("mode", "unique", "field", "customer.country"))).isEqualTo(List.of("TR", "DE"));
    }

    @Test
    void computesNumbers() {
        assertThat(result(Map.of("mode", "sum", "field", "total"))).isEqualTo(240.5);
        assertThat(result(Map.of("mode", "avg", "field", "id"))).isEqualTo(2L);
        assertThat(result(Map.of("mode", "min", "field", "total"))).isEqualTo(40L);
        assertThat(result(Map.of("mode", "max", "field", "total"))).isEqualTo(120L);
        assertThat(aggregate(Map.of("mode", "sum", "field", "total")).output()).containsEntry("count", 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsByField() {
        StepLog step = aggregate(Map.of("mode", "groupBy", "field", "customer.country"));
        Map<String, List<Object>> groups = (Map<String, List<Object>>) step.output().get("result");
        assertThat(groups.keySet()).containsExactly("TR", "DE");
        assertThat(groups.get("TR")).hasSize(2);
        assertThat(step.output().get("counts")).isEqualTo(Map.of("TR", 2, "DE", 1));
    }

    @Test
    void reportsBadInput() {
        assertThat(aggregate(Map.of("mode", "sum", "field", "customer")).error()).contains("customer");
        assertThat(aggregate(Map.of("mode", "sum")).error()).contains("field");
        assertThat(aggregate(Map.of("mode", "median", "field", "total")).error()).contains("median");
        assertThat(aggregate(Map.of("mode", "count", "items", "{{ input.missing }}")).error()).contains("null");
    }

    @Test
    void summarisesTheDoneOutputOfALoop() {
        Workflow wf = new Workflow("loop-aggregate", "loop + aggregate", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("each", "loop", Map.of("items", "{{ input.orders }}")),
                new NodeDef("price", "set", Map.of("keepInput", false, "values", Map.of(
                        "id", "{{ input.id }}", "withVat", "{{ input.id * 120 }}"))),
                new NodeDef("total", "aggregate", Map.of("items", "{{ input.items }}", "mode", "sum", "field", "withVat"))
        ), List.of(new Connection("start", "each", null), new Connection("each", "price", "item"),
                new Connection("each", "total", "done")));

        ExecutionContext ctx = engine.run(wf, Map.of("orders", ORDERS));

        assertThat(ctx.nodeOutputs().get("total")).isEqualTo(Map.of("result", 720L, "count", 3));
    }
}
