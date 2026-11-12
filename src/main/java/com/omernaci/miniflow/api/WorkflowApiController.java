package com.omernaci.miniflow.api;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.common.i18n.Messages;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.engine.expression.ExpressionResolver;
import com.omernaci.miniflow.execution.Execution;
import com.omernaci.miniflow.execution.ExecutionService;
import com.omernaci.miniflow.execution.ExecutionStore;
import com.omernaci.miniflow.node.NodeTypeInfo;
import com.omernaci.miniflow.node.core.RespondNode;
import com.omernaci.miniflow.variable.VariableService;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@RestController
public class WorkflowApiController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowApiController.class);

    private final WorkflowService store;
    private final ExecutionService executions;
    private final ExecutionStore executionStore;
    private final WorkflowEngine engine;
    private final ObjectMapper mapper;
    private final VariableService variables;
    private final ExpressionResolver expressions;

    public WorkflowApiController(WorkflowService store, ExecutionService executions,
                                 ExecutionStore executionStore, WorkflowEngine engine,
                                 ObjectMapper mapper, VariableService variables,
                                 ExpressionResolver expressions) {
        this.expressions = expressions;
        this.store = store;
        this.executions = executions;
        this.executionStore = executionStore;
        this.engine = engine;
        this.mapper = mapper;
        this.variables = variables;
    }

    @GetMapping("/api/workflows")
    public Collection<Workflow> list() {
        return store.all();
    }

    @GetMapping("/api/workflows/{id}")
    public Workflow get(@PathVariable String id) {
        return find(id);
    }

    @PutMapping("/api/workflows/{id}")
    public Workflow save(@PathVariable String id, @RequestBody Workflow body) {
        return store.save(new Workflow(id, body.name(), body.active(), body.nodes(), body.connections()));
    }

    @PostMapping("/api/workflows/import")
    public Map<String, Object> importWorkflows(@RequestBody String json,
                                               @RequestParam(defaultValue = "false") boolean overwrite) {
        List<String> ids = store.importJson(json, overwrite).stream().map(Workflow::id).toList();
        return Map.of("imported", ids.size(), "ids", ids);
    }

    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (store.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/api/node-types")
    public List<NodeTypeInfo> nodeTypes() {
        return engine.nodeTypes();
    }

    @GetMapping("/api/workflows/{id}/last-run")
    public ResponseEntity<Map<String, Object>> lastRun(@PathVariable String id) {
        find(id);
        return executionStore.recentForWorkflow(id, 1).stream().findFirst()
                .map(ex -> {
                    Map<String, Object> nodes = new LinkedHashMap<>();
                    ex.steps().forEach(step -> {
                        if (step.output() != null) {
                            nodes.put(step.nodeId(), step.output());
                        }
                    });
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("executionId", ex.id());
                    body.put("startedAt", ex.startedAt());
                    body.put("mode", ex.mode());
                    body.put("trigger", ex.trigger());
                    body.put("nodes", nodes);
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record PreviewRequest(Map<String, Object> params, Map<String, Object> input,
                                 Map<String, Object> trigger, Map<String, Object> nodes) {
    }

    @PostMapping("/api/expressions/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest req) {
        return expressions.preview(req.params(), req.input(), req.trigger(), req.nodes());
    }

    @PostMapping("/api/workflows/{id}/run")
    public Execution run(@PathVariable String id, @RequestBody(required = false) Map<String, Object> data) {
        return executions.run(find(id), data, "manual");
    }

    @PostMapping("/webhook/{path}")
    public ResponseEntity<?> webhookPost(@PathVariable String path, HttpServletRequest request)
            throws IOException {
        Workflow wf = activeWebhook(path);
        Map<String, Object> data;
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
        if (contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            verifySignature(wf, null, request);
            Map<String, Object> form = new LinkedHashMap<>();
            request.getParameterMap().forEach((k, v) -> form.put(k, v.length == 1 ? v[0] : List.of(v)));
            data = form;
        } else {
            byte[] raw = StreamUtils.copyToByteArray(request.getInputStream());
            verifySignature(wf, raw, request);
            data = parseBody(raw);
        }
        data.put("_headers", headers(request));
        return execute(wf, data);
    }

    @GetMapping("/webhook/{path}")
    public ResponseEntity<?> webhookGet(@PathVariable String path,
                                        @RequestParam Map<String, String> query,
                                        HttpServletRequest request) {
        Workflow wf = activeWebhook(path);
        verifySignature(wf, new byte[0], request);
        Map<String, Object> data = new LinkedHashMap<>(query);
        data.put("_headers", headers(request));
        return execute(wf, data);
    }

    private Workflow activeWebhook(String path) {
        Workflow wf = store.findByWebhookPath(path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.webhook.notFound", path)));
        if (!wf.active()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.webhook.inactive", wf.id()));
        }
        return wf;
    }

    private Map<String, Object> parseBody(byte[] raw) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (raw.length == 0 || new String(raw, StandardCharsets.UTF_8).isBlank()) {
            return data;
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (node.isObject()) {
                data.putAll(mapper.convertValue(node, new TypeReference<Map<String, Object>>() { }));
            } else if (node.isArray()) {
                data.put("items", mapper.convertValue(node, new TypeReference<List<Object>>() { }));
            } else {
                data.put("value", mapper.convertValue(node, Object.class));
            }
            return data;
        } catch (JacksonException e) {
            throw new MiniFlowException("error.body.invalidJson", e.getOriginalMessage());
        }
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> out = new TreeMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase();
            if (!lower.equals("authorization") && !lower.equals("cookie")) {
                out.put(lower, request.getHeader(name));
            }
        }
        return out;
    }

    private void verifySignature(Workflow wf, byte[] raw, HttpServletRequest request) {
        Optional<String> varName = wf.triggerParam("signatureSecretVar");
        if (varName.isEmpty()) {
            return;
        }
        String secret = variables.get(varName.get()).orElse(null);
        if (secret == null || secret.isBlank()) {
            log.warn("Workflow '{}': variable '{}' is not defined, webhook signature is NOT verified",
                    wf.id(), varName.get());
            return;
        }
        String header = request.getHeader("X-Hub-Signature-256");
        if (header == null || raw == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.signature.missing"));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(raw));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    header.trim().getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.signature.invalid"));
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<?> execute(Workflow wf, Map<String, Object> data) {
        Execution ex = executions.run(wf, data, "webhook");
        if ("SUCCESS".equals(ex.status())) {
            Optional<ResponseEntity<?>> custom = customResponse(ex);
            if (custom.isPresent()) {
                return custom.get();
            }
        }
        var last = ex.steps().isEmpty() ? null : ex.steps().get(ex.steps().size() - 1);
        Object payload = last == null ? null : (last.error() != null ? Map.of("error", last.error()) : last.output());
        HttpStatus status = "SUCCESS".equals(ex.status()) ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(new WebhookResponse(ex.id(), ex.status(), payload));
    }

    /** Honours a {@code respond} node (issue #12): the last successful one sets status, headers and body. */
    private Optional<ResponseEntity<?>> customResponse(Execution ex) {
        var respond = ex.steps().stream()
                .filter(s -> "respond".equals(s.type()) && "SUCCESS".equals(s.status()) && s.output() != null
                        && Boolean.TRUE.equals(s.output().get(RespondNode.MARKER)))
                .reduce((first, second) -> second);
        if (respond.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> out = respond.get().output();
        int status = out.get("status") instanceof Number n ? n.intValue() : 200;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (out.get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> builder.header(String.valueOf(k), String.valueOf(v)));
        }
        return Optional.of(builder.body(out.get("body")));
    }

    public record WebhookResponse(String executionId, String status, Object data) {
    }

    @GetMapping("/api/executions")
    public List<Execution> recentExecutions() {
        return executionStore.recent(50);
    }

    @GetMapping("/api/executions/{id}")
    public Execution execution(@PathVariable String id) {
        return executionStore.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.notFound.execution", id)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private Workflow find(String id) {
        return store.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.notFound.workflow", id)));
    }
}
