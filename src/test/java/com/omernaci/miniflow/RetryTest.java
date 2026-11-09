package com.omernaci.miniflow;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.workflow.RetryPolicy;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.omernaci.miniflow.workflow.WorkflowService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RetryTest {

    static HttpServer server;
    static final AtomicInteger requests = new AtomicInteger();
    static volatile int failFirst;

    @Autowired
    WorkflowEngine engine;

    @Autowired
    WorkflowService store;

    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flaky", exchange -> {
            int n = requests.incrementAndGet();
            boolean fail = n <= failFirst;
            byte[] body = (fail ? "{\"error\":\"temporarily unavailable\"}" : "{\"ok\":true,\"attempt\":" + n + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        requests.set(0);
    }

    private Workflow flakyCall(RetryPolicy retry) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky";
        return new Workflow("retry-test", "retry", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("call", "http", Map.of("method", "GET", "url", url), null, null, retry)
        ), List.of(new Connection("start", "call", null)));
    }

    private StepLog callStep(ExecutionContext ctx) {
        return ctx.steps().stream().filter(s -> s.nodeId().equals("call")).findFirst().orElseThrow();
    }

    @Test
    void succeedsOnSecondAttempt() {
        failFirst = 1;
        ExecutionContext ctx = engine.run(flakyCall(new RetryPolicy(3, 1L, 1.0)), Map.of());

        StepLog step = callStep(ctx);
        assertThat(step.status()).isEqualTo("SUCCESS");
        assertThat(step.attempts()).isEqualTo(2);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(step.output()).containsEntry("status", 200);
    }

    @Test
    void failsAfterAllAttempts() {
        failFirst = 100;
        ExecutionContext ctx = engine.run(flakyCall(new RetryPolicy(2, 1L, 1.0)), Map.of());

        StepLog step = callStep(ctx);
        assertThat(step.status()).isEqualTo("ERROR");
        assertThat(step.attempts()).isEqualTo(3);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void withoutRetryRunsOnce() {
        failFirst = 100;
        ExecutionContext ctx = engine.run(flakyCall(null), Map.of());

        StepLog step = callStep(ctx);
        assertThat(step.status()).isEqualTo("ERROR");
        assertThat(step.attempts()).isEqualTo(1);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void delayGrowsWithBackoffAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(5, 1000L, 2.0);
        assertThat(policy.delayBeforeRetry(1)).isEqualTo(1000);
        assertThat(policy.delayBeforeRetry(3)).isEqualTo(4000);
        assertThat(new RetryPolicy(5, 20_000L, 3.0).delayBeforeRetry(2)).isEqualTo(RetryPolicy.MAX_DELAY_MS);
        assertThat(new RetryPolicy(1, null, null)).isEqualTo(new RetryPolicy(1, 1000L, 2.0));
    }

    @Test
    void invalidRetryIsRejectedOnSave() {
        Workflow wf = new Workflow("retry-invalid", "invalid", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("call", "log", Map.of("message", "x"), null, null, new RetryPolicy(9, 1000L, 2.0))
        ), List.of(new Connection("start", "call", null)));

        assertThatThrownBy(() -> store.save(wf))
                .isInstanceOf(MiniFlowException.class)
                .hasMessageContaining("call");
        assertThat(store.find("retry-invalid")).isEmpty();
    }

    @Test
    void retryIsKeptThroughImportAndExport() {
        String json = """
                {"id": "retry-roundtrip", "name": "Retry roundtrip", "nodes": [
                  {"id": "start", "type": "trigger"},
                  {"id": "call", "type": "log", "params": {"message": "hi"}, "retry": {"times": 2, "delayMs": 500}}
                ], "connections": [{"from": "start", "to": "call"}]}
                """;
        store.importJson(json, true);

        Workflow loaded = store.find("retry-roundtrip").orElseThrow();
        assertThat(loaded.node("call").orElseThrow().retry()).isEqualTo(new RetryPolicy(2, 500L, 2.0));
        assertThat(loaded.node("start").orElseThrow().retry()).isNull();

        String exported = mapper.writeValueAsString(loaded);
        assertThat(exported).contains("\"retry\"");
        assertThat(exported.indexOf("\"retry\"")).isEqualTo(exported.lastIndexOf("\"retry\""));
        store.delete("retry-roundtrip");
    }
}
