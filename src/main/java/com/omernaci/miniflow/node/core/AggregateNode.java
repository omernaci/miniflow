package com.omernaci.miniflow.node.core;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/** Summarises a list, typically the {@code done} output of a loop: collect, count, sum, average, group, join. */
@Component
public class AggregateNode implements NodeExecutor {

    static final List<String> MODES = List.of("collect", "count", "sum", "avg", "min", "max", "groupBy", "join", "unique");

    @Override
    public String type() {
        return "aggregate";
    }

    @Override
    public String label() {
        return "Aggregate";
    }

    @Override
    public String description() {
        return "Summarises a list: collect a field, count, sum, average, min/max, group or join.";
    }

    @Override
    public Map<String, Object> defaultParams() {
        return Map.of("items", "{{ input.items }}", "mode", "collect");
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.text("items").mandatory().placeholder("{{ input.items }}"),
                ParamField.select("mode", MODES.toArray(String[]::new)).mandatory(),
                ParamField.text("field").placeholder("total")
                        .when("mode", "collect", "sum", "avg", "min", "max", "groupBy", "join", "unique"),
                ParamField.text("separator").placeholder(", ").when("mode", "join"));
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Object itemsParam = params.get("items");
        if (!(itemsParam instanceof Collection<?> items)) {
            throw new MiniFlowException("error.aggregate.notList",
                    itemsParam == null ? "null" : itemsParam.getClass().getSimpleName());
        }
        String mode = Params.str(params, "mode", "collect");
        if (!MODES.contains(mode)) {
            throw new MiniFlowException("error.aggregate.mode", mode, String.join(", ", MODES));
        }
        String field = Params.str(params, "field");
        boolean hasField = field != null && !field.isBlank();
        if (!hasField && List.of("sum", "avg", "min", "max", "groupBy").contains(mode)
                && items.stream().anyMatch(i -> i instanceof Map<?, ?>)) {
            throw new MiniFlowException("error.param.required", "field");
        }
        List<Object> values = new ArrayList<>();
        for (Object item : items) {
            values.add(hasField ? read(item, field.trim()) : item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        switch (mode) {
            case "collect" -> out.put("result", values);
            case "count" -> out.put("result", items.size());
            case "sum" -> out.put("result", plain(numbers(values, field).stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
            case "avg" -> {
                List<BigDecimal> numbers = numbers(values, field);
                out.put("result", numbers.isEmpty() ? null : plain(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(numbers.size()), 10, RoundingMode.HALF_UP)));
            }
            case "min" -> out.put("result", numbers(values, field).stream().min(BigDecimal::compareTo).map(AggregateNode::plain).orElse(null));
            case "max" -> out.put("result", numbers(values, field).stream().max(BigDecimal::compareTo).map(AggregateNode::plain).orElse(null));
            case "groupBy" -> {
                Map<String, List<Object>> groups = new LinkedHashMap<>();
                List<Object> list = new ArrayList<>(items);
                for (int i = 0; i < list.size(); i++) {
                    groups.computeIfAbsent(String.valueOf(values.get(i)), k -> new ArrayList<>()).add(list.get(i));
                }
                Map<String, Object> counts = new LinkedHashMap<>();
                groups.forEach((k, v) -> counts.put(k, v.size()));
                out.put("result", groups);
                out.put("counts", counts);
            }
            case "join" -> {
                StringJoiner joiner = new StringJoiner(params.get("separator") == null ? ", " : String.valueOf(params.get("separator")));
                values.stream().filter(Objects::nonNull).forEach(v -> joiner.add(String.valueOf(v)));
                out.put("result", joiner.toString());
            }
            case "unique" -> out.put("result", new ArrayList<>(new LinkedHashSet<>(values)));
            default -> throw new IllegalStateException(mode);
        }
        out.put("count", items.size());
        return NodeResult.of(out);
    }

    /** Reads a (dotted) field from a map item; {@code null} when it is missing. */
    static Object read(Object item, String path) {
        Object current = item;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static List<BigDecimal> numbers(List<Object> values, String field) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (Object v : values) {
            if (v == null || String.valueOf(v).isBlank()) {
                continue;
            }
            try {
                numbers.add(new BigDecimal(String.valueOf(v).trim()));
            } catch (NumberFormatException e) {
                throw new MiniFlowException("error.aggregate.notNumber", field == null ? "item" : field, v);
            }
        }
        return numbers;
    }

    /** Whole numbers as Long, others as Double, so expressions and JSON show 12 rather than 12.0000000000. */
    private static Number plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
            return stripped.longValueExact();
        }
        return stripped.doubleValue();
    }
}
