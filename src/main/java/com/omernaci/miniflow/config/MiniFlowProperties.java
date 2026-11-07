package com.omernaci.miniflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

@ConfigurationProperties(prefix = "miniflow")
public record MiniFlowProperties(Map<String, String> vars,
                                 @DefaultValue("14") int executionRetentionDays,
                                 @DefaultValue("true") boolean seedExamples,
                                 @DefaultValue("en") String defaultLocale,
                                 String encryptionKey,
                                 @DefaultValue("classpath:workflows/*.json") String seedLocation) {

    public MiniFlowProperties {
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (vars != null) {
            ci.putAll(vars);
        }
        vars = Collections.unmodifiableMap(ci);
    }
}
