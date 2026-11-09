package com.omernaci.miniflow.node.core;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the nodes connected to the {@code item} output once per list item, then continues on {@code done}
 * with the collected results. The iteration itself is driven by the workflow engine.
 */
@Component
public class LoopNode implements NodeExecutor {

    public static final String TYPE = "loop";
    public static final String ITEM = "item";
    public static final String DONE = "done";
    public static final int DEFAULT_MAX_ITEMS = 100;
    public static final int MAX_ITEMS = 1000;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String label() {
        return "Loop";
    }

    @Override
    public String description() {
        return "Runs the item branch once per list item, then continues on done with all results.";
    }

    @Override
    public List<String> outputs() {
        return List.of(ITEM, DONE);
    }

    @Override
    public Map<String, Object> defaultParams() {
        return Map.of("items", "{{ input.rows }}", "maxItems", DEFAULT_MAX_ITEMS);
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.text("items").mandatory().placeholder("{{ query.rows }}"),
                ParamField.number("maxItems"));
    }

    /** Validates the list; the engine then runs the {@code item} branch for each element of {@code items}. */
    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Object items = params.get("items");
        if (!(items instanceof Collection<?> list)) {
            throw new MiniFlowException("error.loop.notList", items == null ? "null" : items.getClass().getSimpleName());
        }
        int maxItems = Params.integer(params, "maxItems", DEFAULT_MAX_ITEMS);
        if (maxItems < 1 || maxItems > MAX_ITEMS) {
            throw new MiniFlowException("error.loop.maxItemsRange", MAX_ITEMS);
        }
        if (list.size() > maxItems) {
            throw new MiniFlowException("error.loop.tooMany", list.size(), maxItems);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", List.copyOf(list.stream().map(i -> (Object) (i == null ? Map.of() : i)).toList()));
        return NodeResult.branch(out, ITEM);
    }

    /** The input of the item branch: maps are passed as they are, other values as {@code { "value": item }}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> itemInput(Object item) {
        if (item instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", item);
        return wrapped;
    }
}
