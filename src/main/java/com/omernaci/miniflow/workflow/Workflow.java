package com.omernaci.miniflow.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.omernaci.miniflow.common.exception.MiniFlowException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Workflow(String id, String name, boolean active, List<NodeDef> nodes, List<Connection> connections) {

    public record NodeDef(String id, String type, Map<String, Object> params, Integer x, Integer y,
                          @JsonInclude(JsonInclude.Include.NON_NULL) RetryPolicy retry,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String onError) {

        public static final String ON_ERROR_STOP = "stop";
        public static final String ON_ERROR_BRANCH = "branch";
        public static final String ERROR_BRANCH = "error";

        public NodeDef {
            params = params == null ? Map.of() : params;
        }

        public NodeDef(String id, String type, Map<String, Object> params, Integer x, Integer y, RetryPolicy retry) {
            this(id, type, params, x, y, retry, null);
        }

        public NodeDef(String id, String type, Map<String, Object> params, Integer x, Integer y) {
            this(id, type, params, x, y, null, null);
        }

        public NodeDef(String id, String type, Map<String, Object> params) {
            this(id, type, params, null, null, null, null);
        }

        /** True when a failure continues on the {@value #ERROR_BRANCH} output instead of stopping the workflow. */
        public boolean continuesOnError() {
            return ON_ERROR_BRANCH.equals(onError);
        }
    }

    public record Connection(String from, String to, String branch) {

        public Connection {
            branch = (branch == null || branch.isBlank()) ? "main" : branch;
        }
    }

    public Workflow {
        nodes = nodes == null ? List.of() : nodes;
        connections = connections == null ? List.of() : connections;
    }

    public Workflow(String id, String name, List<NodeDef> nodes, List<Connection> connections) {
        this(id, name, false, nodes, connections);
    }

    public Workflow withActive(boolean value) {
        return new Workflow(id, name, value, nodes, connections);
    }

    public Optional<NodeDef> node(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    public NodeDef startNode() {
        return nodes.stream()
                .filter(n -> "trigger".equals(n.type()))
                .findFirst()
                .orElseThrow(() -> new MiniFlowException("error.workflow.noTrigger", id));
    }

    public Optional<String> webhookPath() {
        return triggerParam("path");
    }

    public Optional<String> cron() {
        return triggerParam("cron");
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> listenConfig() {
        return nodes.stream()
                .filter(n -> "trigger".equals(n.type()))
                .findFirst()
                .map(n -> n.params().get("listen"))
                .filter(v -> v instanceof Map<?, ?> m && m.get("type") != null && !String.valueOf(m.get("type")).isBlank())
                .map(v -> (Map<String, Object>) v);
    }

    public Optional<String> triggerParam(String key) {
        return nodes.stream()
                .filter(n -> "trigger".equals(n.type()))
                .findFirst()
                .map(n -> n.params().get(key))
                .map(v -> String.valueOf(v).trim())
                .filter(v -> !v.isEmpty());
    }

    public List<String> nextNodes(String fromId, String branch) {
        return connections.stream()
                .filter(c -> c.from().equals(fromId) && c.branch().equals(branch))
                .map(Connection::to)
                .toList();
    }
}
