package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.execution.Execution;
import com.omernaci.miniflow.execution.ExecutionStore;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AutomationTest {

    @Autowired
    WorkflowService store;

    @Autowired
    ExecutionStore executions;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void orderFlow() {
        TestWorkflows.ensureOrderFlow(store);
    }

    @Test
    void starterWorkflowsAreValid() throws Exception {
        List<Path> files;
        try (Stream<Path> list = Files.list(Path.of("src/main/resources/workflows"))) {
            files = list.filter(f -> f.toString().endsWith(".json")).toList();
        }
        for (Path file : files) {
            List<Workflow> imported = store.importJson(Files.readString(file, StandardCharsets.UTF_8), true);
            assertThat(imported).as(file.toString()).hasSize(1);
        }
    }

    @Test
    void workflowRoundTripsThroughDatabase() {
        Workflow wf = new Workflow("db-test", "DB test", true, List.of(
                new NodeDef("start", "trigger", Map.of("path", "db-test"), 10, 20),
                new NodeDef("log", "log", Map.of("message", "{{ input.x }}"), 200, 20)
        ), List.of(new Connection("start", "log", null)));
        store.save(wf);

        Workflow loaded = store.find("db-test").orElseThrow();
        assertThat(loaded.active()).isTrue();
        assertThat(loaded.nodes()).hasSize(2);
        assertThat(loaded.nodes().get(0).x()).isEqualTo(10);
        assertThat(loaded.connections()).containsExactly(new Connection("start", "log", "main"));

        assertThat(store.delete("db-test")).isTrue();
        assertThat(store.find("db-test")).isEmpty();
    }

    @Test
    void expressionsSupportSafeMethodsMissingKeysAndVars() {
        Workflow wf = new Workflow("expr", "expr", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("x", "set", Map.of("keepInput", false, "values", Map.of(
                        "valid", "{{ input.email != null and input.email.contains('@') }}",
                        "email", "{{ input.email.trim().toLowerCase() }}",
                        "missing", "{{ input.yok == null }}",
                        "greeting", "{{ vars.greeting }} {{ input.name?.trim() }}",
                        "domain", "{{ input.email.trim().split('@')[1] }}")))
        ), List.of(new Connection("start", "x", null)));

        ExecutionContext ctx = engine.run(wf, Map.of("email", " ALI@Test.com ", "name", " Ali "));
        assertThat(ctx.steps().get(1).status()).isEqualTo("SUCCESS");
        assertThat(ctx.nodeOutputs().get("x")).isEqualTo(Map.of(
                "valid", true, "email", "ali@test.com", "missing", true,
                "greeting", "Merhaba Ali", "domain", "Test.com"));
    }

    @Test
    void reflectionAndStaticMethodsAreBlocked() {
        for (String expr : List.of(
                "{{ input.name.getClass() }}",
                "{{ input.name.getClass().forName('java.lang.Runtime') }}",
                "{{ input.n.valueOf(1) }}",
                "{{ T(java.lang.Runtime).getRuntime() }}",
                "{{ new java.io.File('x') }}")) {
            Workflow wf = new Workflow("evil", "evil", List.of(
                    new NodeDef("start", "trigger", Map.of()),
                    new NodeDef("x", "set", Map.of("values", Map.of("v", expr)))
            ), List.of(new Connection("start", "x", null)));
            ExecutionContext ctx = engine.run(wf, Map.of("name", "a", "n", 5));
            assertThat(ctx.steps().get(1).status()).as(expr).isEqualTo("ERROR");
        }
    }

    @Test
    void invalidCronAndDuplicateWebhookPathAreRejected() throws Exception {
        mvc.perform(put("/api/workflows/bad-cron")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"nodes\":[{\"id\":\"s\",\"type\":\"trigger\",\"params\":{\"cron\":\"her gün\"}}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("cron")));

        assertThatThrownBy(() -> store.save(new Workflow("copy", "copy", List.of(
                new NodeDef("s", "trigger", Map.of("path", "orders"))), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order-flow");
    }

    @Test
    void webhookWorksOnlyWhenActiveAndReturnsLastNodeOutput() throws Exception {
        store.save(new Workflow("hook", "Hook", false, List.of(
                new NodeDef("s", "trigger", Map.of("path", "hook")),
                new NodeDef("reply", "set", Map.of("keepInput", false,
                        "values", Map.of("hello", "{{ input.name }}")))
        ), List.of(new Connection("s", "reply", null))));

        mvc.perform(get("/webhook/hook").param("name", "Ali"))
                .andExpect(status().isNotFound());

        store.setActive("hook", true);

        mvc.perform(get("/webhook/hook").param("name", "Ali"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.hello").value("Ali"));

        mvc.perform(post("/webhook/hook").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Veli\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hello").value("Veli"));

        assertThat(executions.recentForWorkflow("hook", 10))
                .hasSize(2)
                .allMatch(e -> e.mode().equals("webhook"));
        store.delete("hook");
    }

    @Test
    void cronTriggerRunsActiveWorkflowAndStopsWhenDeactivated() throws Exception {
        store.save(new Workflow("tick", "Tick", true, List.of(
                new NodeDef("s", "trigger", Map.of("cron", "* * * * * *")),
                new NodeDef("log", "log", Map.of("message", "tick {{ trigger.firedAt }}"))
        ), List.of(new Connection("s", "log", null))));

        List<Execution> runs = List.of();
        for (int i = 0; i < 50 && runs.isEmpty(); i++) {
            Thread.sleep(100);
            runs = executions.recentForWorkflow("tick", 10);
        }
        assertThat(runs).isNotEmpty();
        assertThat(runs.get(0).mode()).isEqualTo("schedule");
        assertThat(runs.get(0).status()).isEqualTo("SUCCESS");

        store.setActive("tick", false);
        Thread.sleep(1200);
        int countAfterStop = executions.recentForWorkflow("tick", 100).size();
        Thread.sleep(1500);
        assertThat(executions.recentForWorkflow("tick", 100)).hasSize(countAfterStop);
        store.delete("tick");
    }
}
