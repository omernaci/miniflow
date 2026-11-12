package com.omernaci.miniflow.node.core;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the HTTP response of a webhook: status code, headers and body.
 * The node only records the response on its output; {@code WorkflowApiController}
 * reads it when the workflow was started by a webhook. Without this node the
 * webhook keeps returning the last node output as before.
 */
@Component
public class RespondNode implements NodeExecutor {

    /** Marks the output so the controller can tell this step apart from a map a node happens to return. */
    public static final String MARKER = "_respond";

    @Override
    public String type() {
        return "respond";
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.number("status"),
                ParamField.keyValue("headers"),
                ParamField.code("body"));
    }

    @Override
    public Map<String, Object> defaultParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", 200);
        p.put("body", "{{ input }}");
        return p;
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(MARKER, true);
        out.put("status", Params.integer(params, "status", 200));
        Object headers = params.get("headers");
        if (headers instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> clean = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    clean.put(String.valueOf(k), v);
                }
            });
            out.put("headers", clean);
        }
        if (params.containsKey("body")) {
            out.put("body", params.get("body"));
        }
        return NodeResult.of(out);
    }
}
