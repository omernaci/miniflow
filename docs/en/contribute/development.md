# Development guide

## Build and test

Requirements: Java 21. The commands below use the Maven Wrapper (`./mvnw`); a locally installed Maven 3.9+ works too.

```bash
./mvnw verify                  # compile and run all tests (no external services needed)
./mvnw spring-boot:run         # start on http://localhost:8080 with an H2 database in ./data
```

Tests use an in-memory H2 database (`src/test/resources/application-test.properties`) and local fake servers
for HTTP, SMTP, GitHub, Google and AI providers, so they never call real services.

## Project structure

The code is organised by feature, similar to
[Apache DolphinScheduler](https://github.com/apache/dolphinscheduler/tree/dev/dolphinscheduler-service/src/main/java/org/apache/dolphinscheduler/service).

```
com.omernaci.miniflow
├── MiniFlowApplication
├── config              MiniFlowProperties, locale configuration
├── common
│   ├── exception       MiniFlowException (localised errors)
│   └── i18n            Messages
├── workflow            Workflow model, WorkflowService (validation, import), JPA entity and repository
├── engine              WorkflowEngine, ExecutionContext
│   └── expression      {{ }} expression resolver and helper functions
├── node                NodeExecutor SPI, NodeResult, ParamField, NodeTypeInfo
│   ├── support         Params, HttpSupport
│   ├── core            trigger, if, set, log, wait, dedupe
│   ├── http            http, rss
│   ├── data            database, spreadsheet
│   ├── notify          slack, email
│   ├── queue           kafka, rabbitmq, redis
│   ├── ai              ai (OpenAI-compatible, Anthropic, Gemini)
│   ├── github          github
│   └── google          googleSheets
├── execution           ExecutionService, execution history
├── scheduler           cron schedules
├── queue               queue connections and listeners (queue triggers)
├── credential          credential types, AES-GCM encryption
├── variable            {{ vars.* }}
├── dashboard           dashboard statistics
├── api                 REST API and webhooks
└── web                 Thymeleaf controllers
    └── view            view helpers
```

Resources:

```
src/main/resources/
├── templates/          Thymeleaf pages
├── static/             editor (Drawflow), CSS, icons
├── workflows/          starter workflows loaded into an empty database
└── messages*.properties  UI and error messages (en, tr)
examples/               importable example workflows and demo SQL
docs/                   documentation (en, tr)
```

## Adding a node

Implement `NodeExecutor` as a Spring `@Component` in a package under `node`. The engine and the editor palette
discover it automatically.

```java
package com.omernaci.miniflow.node.core;

@Component
public class UppercaseNode implements NodeExecutor {

    @Override
    public String type() {
        return "uppercase";
    }

    @Override
    public Map<String, Object> defaultParams() {
        return Map.of("field", "name");
    }

    @Override
    public List<ParamField> fields() {
        return List.of(ParamField.text("field").mandatory());
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        String field = Params.required(params, "field");
        Map<String, Object> out = new LinkedHashMap<>(input);
        out.put(field, String.valueOf(input.get(field)).toUpperCase());
        return NodeResult.of(out);
    }
}
```

- Branching: return `NodeResult.branch(output, "true")` and override `outputs()`.
- Labels: add `node.uppercase.label`, `node.uppercase.desc` and `node.uppercase.field.field` to
  `messages.properties` and `messages_tr.properties`.
- Secrets: override `credentialTypes()` and read them with `CredentialService.require(name, "type")`.
  Never read secrets from parameters or expressions.
- Add a test next to the existing ones in `src/test/java`.

## Adding a credential type

Add a constant to the `CredentialType` enum with its fields (`Field.required`, `Field.secret`, `Field.select`, …)
and the labels `cred.type.<id>` / `cred.field.<id>.<field>` to the message files.

## Adding a language

Copy `messages.properties` to e.g. `messages_de.properties`, translate it and add the language link in
`templates/fragments.html`.

## Code style

- Java 21, four-space indentation, one class per file.
- Code, identifiers, log messages and comments in English. User-facing text belongs in the message files.
- Write comments only where the code cannot explain itself; public extension points (`NodeExecutor`, `ParamField`)
  carry Javadoc.
- No secrets in code, tests or examples. Configuration comes from environment variables (see `.env`).

## Commit messages and pull requests

- Use the form `[Module] Short description`, e.g. `[Node] Add Microsoft Teams node` or `[Editor] Fix form refresh`.
- Keep pull requests focused; describe what changed and how you tested it (see the pull request template).
- `./mvnw verify` must pass.
