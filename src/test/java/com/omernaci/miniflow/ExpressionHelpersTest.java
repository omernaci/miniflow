package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.engine.expression.ExpressionResolver;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExpressionHelpersTest {

    @Autowired WorkflowEngine engine;
    @Autowired ExpressionResolver resolver;
    @Autowired MockMvc mvc;

    @Autowired
    WorkflowService workflows;

    @BeforeEach
    void orderFlow() {
        TestWorkflows.ensureOrderFlow(workflows);
    }

    private Object eval(String expression) {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("total", 1800);
        first.put("items", List.of(Map.of("name", "A", "price", 10.5), Map.of("name", "B", "price", 4)));
        first.put("empty", "");
        Workflow wf = new Workflow("expr-helpers", "x", List.of(
                new NodeDef("s", "trigger", Map.of()),
                new NodeDef("first-step", "set", Map.of("keepInput", false, "values", first)),
                new NodeDef("v", "set", Map.of("keepInput", false, "values", Map.of("r", expression)))
        ), List.of(new Connection("s", "first-step", null), new Connection("first-step", "v", null)));
        ExecutionContext ctx = engine.run(wf, Map.of("customer", "Alice"));
        assertThat(ctx.steps()).allMatch(s -> s.error() == null);
        return ((Map<?, ?>) ctx.nodeOutputs().get("v")).get("r");
    }

    @Test
    void nodeShortNames() {
        assertThat(ExpressionResolver.alias("ping")).isEqualTo("ping");
        assertThat(ExpressionResolver.alias("first-alert")).isEqualTo("firstAlert");
        assertThat(ExpressionResolver.alias("input")).isNull();
        assertThat(ExpressionResolver.alias("join")).isNull();
        assertThat(ExpressionResolver.alias("2nd")).isNull();

        assertThat(eval("{{ firstStep.total }}")).isEqualTo(1800);
        assertThat(eval("{{ nodes['first-step'].total }}")).isEqualTo(1800);
        assertThat(eval("{{ s.customer }}")).isEqualTo("Alice");
        assertThat(eval("{{ input.total == firstStep.total }}")).isEqualTo(true);
    }

    @Test
    void helperFunctions() {
        assertThat(eval("{{ join(firstStep.items.![name], ', ') }}")).isEqualTo("A, B");
        assertThat(eval("{{ join(firstStep.items.!['• ' + name], '\n') }}")).isEqualTo("• A\n• B");
        assertThat(eval("{{ join(firstStep.items.![name]) }}")).isEqualTo("A, B");
        assertThat(eval("{{ default(firstStep.missing, '-') }}")).isEqualTo("-");
        assertThat(eval("{{ default(firstStep.empty, 'n/a') }}")).isEqualTo("n/a");
        assertThat(eval("{{ default(firstStep.total, 0) }}")).isEqualTo(1800);
        assertThat(eval("{{ sum(firstStep.items.![price]) }}")).isEqualTo(14.5);
        assertThat(eval("{{ sum({1, 2, 3}) }}")).isEqualTo(6L);
        assertThat(eval("{{ count(firstStep.items) }}")).isEqualTo(2);
        assertThat(eval("{{ count(firstStep.missing) }}")).isEqualTo(0);
        assertThat(eval("{{ round(2.345, 2) }}")).isEqualTo(2.35);
        assertThat(eval("{{ round(firstStep.total / 7) }}")).isEqualTo(257L);
        assertThat(eval("{{ format('2026-11-05T20:15:00', 'dd.MM.yyyy HH:mm') }}")).isEqualTo("05.11.2026 20:15");
        assertThat(eval("{{ format('2026-11-05', 'dd.MM') }}")).isEqualTo("05.11");
        assertThat(String.valueOf(eval("{{ format(now, 'yyyy') }}"))).matches("\\d{4}");
        assertThat(eval("{{ json(firstStep.items[0]) }}")).asString().contains("\"name\":\"A\"");
        assertThat(eval("Total: {{ firstStep.total }} ({{ count(firstStep.items) }} items)")).isEqualTo("Total: 1800 (2 items)");
    }

    @Test
    void previewResolvesEachParameterWithoutRunningNodes() {
        Map<String, Object> out = resolver.preview(
                Map.of("text", "Order {{ calc.label }} total {{ input.total }}", "bad", "{{ calc.total.foo( }}", "plain", "x"),
                Map.of("total", 1800), Map.of("orderId", 42), Map.of("calc", Map.of("label", "#42")));
        assertThat((Map<String, Object>) out.get("values")).containsEntry("text", "Order #42 total 1800").containsEntry("plain", "x");
        assertThat((Map<String, Object>) out.get("errors")).containsKey("bad");
    }

    @Test
    void previewAndLastRunEndpoints() throws Exception {
        mvc.perform(post("/api/expressions/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"text\":\"{{ ping.status >= 500 ? 'DOWN' : 'UP' }} {{ vars.greeting }}\"},"
                                + "\"input\":{},\"trigger\":{},\"nodes\":{\"ping\":{\"status\":503}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.text").value("DOWN Merhaba"));

        mvc.perform(post("/api/workflows/order-flow/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": 77, \"customer\": \"LastRun\", \"amount\": 10}"));
        mvc.perform(get("/api/workflows/order-flow/last-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger.customer").value("LastRun"))
                .andExpect(jsonPath("$.nodes.calc.label").value(containsString("#77")));

        mvc.perform(get("/workflows/order-flow/edit"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content()
                        .string(containsString("id=\"np-data-tree\"")));
    }
}
