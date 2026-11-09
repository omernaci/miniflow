package com.omernaci.miniflow.node;

import com.omernaci.miniflow.engine.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * A node type. Every implementation is a Spring bean; the engine and the editor
 * discover it automatically, so adding a {@code @Component} is enough to add a node.
 */
public interface NodeExecutor {

    /** Matches the {@code type} of a node in the workflow JSON. */
    String type();

    /**
     * @param params node parameters with all {@code {{ }}} expressions already resolved
     * @param input  output of the previous node
     * @param ctx    execution context with the outputs of all nodes run so far
     */
    NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx);

    default String label() {
        return type();
    }

    default String description() {
        return "";
    }

    default Map<String, Object> defaultParams() {
        return Map.of();
    }

    /** Output branches; {@link NodeResult#branch} must return one of them. */
    default List<String> outputs() {
        return List.of("main");
    }

    default List<String> credentialTypes() {
        return List.of();
    }

    /** Parameters edited by the form in the editor; unknown parameters are kept as-is. */
    default List<ParamField> fields() {
        return List.of();
    }

    default boolean hasInput() {
        return true;
    }

    /**
     * Name of a list parameter whose values become additional outputs placed before {@link #outputs()}
     * (e.g. the cases of a switch), or {@code null} when the outputs are fixed.
     */
    default String dynamicOutputs() {
        return null;
    }
}
