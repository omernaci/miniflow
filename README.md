<p align="center">
  <img src="docs/images/logo.svg" width="72" alt="MiniFlow logo">
</p>

# MiniFlow

[![Build](https://github.com/omernaci/miniflow/actions/workflows/ci.yml/badge.svg)](https://github.com/omernaci/miniflow/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)

## About

MiniFlow is a lightweight, self-hosted workflow automation engine in the spirit of n8n, built with Spring Boot.
Workflows are JSON documents made of nodes and connections; you build and test them in a visual editor and run
them from webhooks, cron schedules or queue messages.

Key features:

- **Visual editor** with drag-and-drop nodes, form-based parameters, a data panel of upstream outputs and live expression preview.
- **Triggers:** webhooks (with GitHub-style signature verification), cron schedules and queue listeners (RabbitMQ, Kafka, Redis).
- **Built-in nodes:** HTTP, if, set, dedupe, wait, log, SQL database, CSV/Excel, RSS, email, Slack (with a Block Kit builder),
  GitHub, Google Sheets, AI (OpenAI-compatible, Anthropic, Gemini), Kafka, RabbitMQ and Redis.
- **Expressions:** `{{ input.total * 1.2 }}`, `{{ nodes['ping'].status }}`, `{{ join(rows.![name], ', ') }}` on a sandboxed SpEL context.
- **Encrypted credentials** (AES-GCM) that never appear in expressions, exports or execution history.
- **Dashboard and execution history** with step-by-step inputs and outputs.
- **Easy to run:** a single jar with an embedded H2 database, or PostgreSQL with Docker Compose.
- **Extensible:** a new node is a single Spring `@Component`.
- UI in English and Turkish.

## Quick start

Requirements: Java 21. Maven is optional; the commands below use the bundled Maven Wrapper (`./mvnw`).

```bash
git clone https://github.com/omernaci/miniflow.git
cd miniflow
./mvnw spring-boot:run
```

Open http://localhost:8080, then import the basic examples and run one:

```bash
curl -X POST 'localhost:8080/api/workflows/import?overwrite=true' -H 'Content-Type: application/json' \
  --data-binary @examples/basics.json
curl -X POST localhost:8080/api/workflows/cart-calculator/run -H 'Content-Type: application/json' \
  -d '{"product":"Headphones","price":450,"qty":2,"coupon":"SAVE10"}'
```

With PostgreSQL and Docker:

```bash
cp .env .env            # set MINIFLOW_DB_PASSWORD and MINIFLOW_ENCRYPTION_KEY
docker compose --profile app up -d --build
```

No secrets are stored in the repository; all configuration comes from environment variables.
See [Installation](docs/en/guide/installation.md) for the full list.

## Screenshots

**Dashboard:** executions over time, success rate, most active workflows, recent failures and upcoming schedules.
![dashboard](docs/images/dashboard.png)

**Workflow editor:** connect nodes on the canvas and edit parameters with forms, including the Slack Block Kit builder.
![editor](docs/images/editor.png)

**Workflows:** search, filter, paginate and act on several workflows at once.
![workflows](docs/images/workflows.png)

## Documentation

| | |
|---|---|
| Getting started | [Introduction](docs/en/about/introduction.md) · [Installation](docs/en/guide/installation.md) · [Your first workflow](docs/en/guide/start/quick-start.md) · [UI tour](docs/en/guide/ui.md) |
| Using MiniFlow | [Concepts](docs/en/guide/concepts.md) · [Expressions](docs/en/guide/expressions.md) · [Nodes](docs/en/guide/nodes.md) · [Credentials](docs/en/guide/credentials.md) · [Variables](docs/en/guide/variables.md) |
| Examples | [Example library](docs/en/guide/examples.md) · [Realistic scenarios](docs/en/guide/scenarios.md) |
| Operations | [Import, export and backups](docs/en/guide/import-export.md) · [Running in production](docs/en/guide/production.md) · [HTTP API](docs/en/guide/api.md) · [FAQ](docs/en/faq.md) |
| Contributing | [Development guide](docs/en/contribute/development.md) · [CONTRIBUTING.md](CONTRIBUTING.md) |

The full documentation index is in [docs/en/README.md](docs/en/README.md).

## Limitations

- No built-in login. Run MiniFlow behind an authenticating reverse proxy, VPN or SSO, and expose only `/webhook/**` publicly.
- Single instance: schedules and queue listeners run in-process. Webhooks are synchronous.
- One item per node (no item lists); no per-node retry or error branch yet.

## Suggestions and bug reports

Please use [GitHub Issues](https://github.com/omernaci/miniflow/issues/new/choose). For security issues, see [SECURITY.md](SECURITY.md).

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and the [development guide](docs/en/contribute/development.md)
to get started.

## License

MiniFlow is licensed under the [Apache License 2.0](LICENSE).
