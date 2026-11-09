package com.omernaci.miniflow.node.core;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Routes the input to the output named after the first case that equals {@code value}, otherwise to {@code default}. */
@Component
public class SwitchNode implements NodeExecutor {

    public static final String DEFAULT = "default";
    public static final int MAX_CASES = 20;

    @Override
    public String type() {
        return "switch";
    }

    @Override
    public String label() {
        return "Switch";
    }

    @Override
    public String description() {
        return "Routes to the output whose case equals the value, otherwise to default.";
    }

    @Override
    public List<String> outputs() {
        return List.of(DEFAULT);
    }

    @Override
    public String dynamicOutputs() {
        return "cases";
    }

    @Override
    public Map<String, Object> defaultParams() {
        return Map.of("value", "{{ input.category }}", "cases", List.of("billing", "technical"));
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.text("value").mandatory().placeholder("{{ input.category }}"),
                ParamField.list("cases").mandatory(),
                ParamField.bool("ignoreCase"));
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        List<String> cases = cases(params.get("cases"));
        Object raw = params.get("value");
        String value = raw == null ? "" : String.valueOf(raw).trim();
        boolean ignoreCase = Params.bool(params, "ignoreCase", false);
        Map<String, Object> out = input == null ? Map.of() : input;
        for (String c : cases) {
            if (ignoreCase ? c.equalsIgnoreCase(value) : c.equals(value)) {
                return NodeResult.branch(out, c);
            }
        }
        return NodeResult.branch(out, DEFAULT);
    }

    /** Validates the cases: non-empty, unique, not "default", at most {@value #MAX_CASES}. */
    static List<String> cases(Object param) {
        List<String> cases = Params.stringList(param);
        if (cases.isEmpty()) {
            throw new MiniFlowException("error.param.required", "cases");
        }
        if (cases.size() > MAX_CASES) {
            throw new MiniFlowException("error.switch.tooMany", MAX_CASES);
        }
        Set<String> seen = new HashSet<>();
        for (String c : cases) {
            if (DEFAULT.equals(c.toLowerCase(Locale.ROOT)) || !seen.add(c)) {
                throw new MiniFlowException("error.switch.invalidCase", c);
            }
        }
        return cases;
    }
}
