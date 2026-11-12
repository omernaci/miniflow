package com.omernaci.miniflow.credential;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum CredentialType {

    HTTP_HEADER("http-header",
            Field.required("headerName", "Authorization"),
            Field.requiredSecret("headerValue")),
    SMTP("smtp",
            Field.required("host", ""),
            Field.required("port", "587"),
            Field.text("username"),
            Field.secret("password"),
            Field.required("from", ""),
            Field.select("starttls", "true", "true", "false")),
    SLACK("slack",
            Field.requiredSecret("webhookUrl")),
    TEAMS("teams",
            Field.requiredSecret("webhookUrl")),
    DISCORD("discord",
            Field.requiredSecret("webhookUrl")),
    DATABASE("database",
            Field.required("jdbcUrl", ""),
            Field.text("username"),
            Field.secret("password")),
    AI("ai",
            Field.select("provider", "openai", "openai", "anthropic", "gemini"),
            Field.secret("apiKey"),
            Field.required("model", ""),
            Field.text("baseUrl")),
    REDIS("redis",
            Field.required("host", "localhost"),
            Field.required("port", "6379"),
            Field.text("username"),
            Field.secret("password"),
            Field.required("database", "0"),
            Field.select("ssl", "false", "false", "true")),
    RABBITMQ("rabbitmq",
            Field.required("host", "localhost"),
            Field.required("port", "5672"),
            Field.required("username", "guest"),
            Field.secret("password"),
            Field.required("virtualHost", "/"),
            Field.select("ssl", "false", "false", "true")),
    KAFKA("kafka",
            Field.required("bootstrapServers", "localhost:9092"),
            Field.select("securityProtocol", "PLAINTEXT", "PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"),
            Field.select("saslMechanism", "PLAIN", "PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"),
            Field.text("username"),
            Field.secret("password")),
    GITHUB("github",
            Field.requiredSecret("token"),
            Field.text("apiBase")),
    GOOGLE("google",
            Field.requiredSecretText("serviceAccountJson"),
            Field.text("apiBase"));

    private final String id;
    private final List<Field> fields;

    CredentialType(String id, Field... fields) {
        this.id = id;
        this.fields = List.of(fields);
    }

    public String id() {
        return id;
    }

    public List<Field> fields() {
        return fields;
    }

    public static Optional<CredentialType> byId(String id) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst();
    }

    public record Field(String name, boolean secret, boolean required, String defaultValue,
                        List<String> options, boolean multiline) {

        static Field text(String name) {
            return new Field(name, false, false, "", List.of(), false);
        }

        static Field required(String name, String defaultValue) {
            return new Field(name, false, true, defaultValue, List.of(), false);
        }

        static Field secret(String name) {
            return new Field(name, true, false, "", List.of(), false);
        }

        static Field requiredSecret(String name) {
            return new Field(name, true, true, "", List.of(), false);
        }

        static Field requiredSecretText(String name) {
            return new Field(name, true, true, "", List.of(), true);
        }

        static Field select(String name, String defaultValue, String... options) {
            return new Field(name, false, true, defaultValue, List.of(options), false);
        }
    }
}
