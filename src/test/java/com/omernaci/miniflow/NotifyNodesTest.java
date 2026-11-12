package com.omernaci.miniflow;

import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotifyNodesTest {

    record Recorded(String path, String body) {
    }

    static HttpServer server;
    static final List<Recorded> requests = new CopyOnWriteArrayList<>();
    static final Map<String, Integer> status = new ConcurrentHashMap<>();

    @Autowired CredentialService credentials;
    @Autowired WorkflowEngine engine;
    @Autowired ObjectMapper mapper;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(ex.getRequestURI().getPath(), body));
            int code = status.getOrDefault(ex.getRequestURI().getPath(), 200);
            if (code == 204) {
                ex.sendResponseHeaders(204, -1);
            } else {
                byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        requests.clear();
        status.clear();
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String credential(String type, String path) {
        String name = type + "-" + UUID.randomUUID().toString().substring(0, 8);
        credentials.save(null, name, type, Map.of("webhookUrl", url(path)));
        return name;
    }

    private StepLog run(String type, Map<String, Object> params, Map<String, Object> input) {
        Workflow wf = new Workflow("notify-" + UUID.randomUUID(), "x", List.of(
                new NodeDef("s", "trigger", Map.of()), new NodeDef("n", type, params)
        ), List.of(new Connection("s", "n", null)));
        ExecutionContext ctx = engine.run(wf, input);
        return ctx.steps().get(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String s) {
        return mapper.readValue(s, Map.class);
    }

    @Test
    void teamsSendsPlainText() {
        String cred = credential("teams", "/teams");
        StepLog step = run("teams", Map.of("credential", cred, "text", "Deploy {{ input.env }} done"),
                Map.of("env", "prod"));

        assertThat(step.status()).isEqualTo("SUCCESS");
        assertThat(step.output()).containsEntry("sent", true).containsEntry("status", 200);
        assertThat(requests).hasSize(1);
        assertThat(json(requests.get(0).body())).containsEntry("text", "Deploy prod done");
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamsWrapsAdaptiveCard() {
        String cred = credential("teams", "/teams");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");

        StepLog step = run("teams", Map.of("credential", cred, "text", "ignored", "card", card), Map.of());

        assertThat(step.status()).isEqualTo("SUCCESS");
        Map<String, Object> body = json(requests.get(0).body());
        assertThat(body).containsEntry("type", "message");
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) body.get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0)).containsEntry("contentType", "application/vnd.microsoft.card.adaptive");
        assertThat((Map<String, Object>) attachments.get(0).get("content")).containsEntry("type", "AdaptiveCard");
    }

    @Test
    void discordSendsContentAndOptionalUsername() {
        String cred = credential("discord", "/discord");
        status.put("/discord", 204);

        StepLog plain = run("discord", Map.of("credential", cred, "text", "Build failed"), Map.of());
        assertThat(plain.status()).isEqualTo("SUCCESS");
        assertThat(plain.output()).containsEntry("sent", true).containsEntry("status", 204);
        Map<String, Object> plainBody = json(requests.get(0).body());
        assertThat(plainBody).containsEntry("content", "Build failed");
        assertThat(plainBody).doesNotContainKey("username");

        StepLog named = run("discord", Map.of("credential", cred, "text", "Nightly ok", "username", "MiniFlow"),
                Map.of());
        assertThat(named.status()).isEqualTo("SUCCESS");
        assertThat(json(requests.get(1).body()))
                .containsEntry("content", "Nightly ok").containsEntry("username", "MiniFlow");
    }

    @Test
    void failedWebhookIsReportedWithoutSecret() {
        String cred = credential("discord", "/discord");
        status.put("/discord", 500);

        StepLog step = run("discord", Map.of("credential", cred, "text", "x"), Map.of());

        assertThat(step.status()).isEqualTo("ERROR");
        assertThat(step.error()).contains("500").doesNotContain("127.0.0.1");
    }
}
