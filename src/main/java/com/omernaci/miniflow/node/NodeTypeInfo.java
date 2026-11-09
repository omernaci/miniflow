package com.omernaci.miniflow.node;

import com.omernaci.miniflow.common.i18n.Messages;

import java.util.List;
import java.util.Map;

public record NodeTypeInfo(String type, String label, String description,
                           Map<String, Object> defaultParams, List<String> outputs, boolean hasInput,
                           List<String> credentialTypes, List<ParamField> fields, String dynamicOutputs) {

    public static NodeTypeInfo from(NodeExecutor e) {
        return new NodeTypeInfo(e.type(), e.label(), e.description(),
                e.defaultParams(), e.outputs(), e.hasInput(), e.credentialTypes(), e.fields(), e.dynamicOutputs());
    }

    public NodeTypeInfo localized() {
        return new NodeTypeInfo(type,
                Messages.getOrDefault("node." + type + ".label", label),
                Messages.getOrDefault("node." + type + ".desc", description),
                defaultParams, outputs, hasInput, credentialTypes,
                fields == null ? List.of() : fields.stream().map(f -> f.localized(type)).toList(), dynamicOutputs);
    }
}
