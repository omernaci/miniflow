# Credentials: setting up each service

Secrets are stored on the **Credentials** page, encrypted with AES-256-GCM, and referenced by name: `"credential": "slack"`.
They can't be read from expressions, are not part of exported JSON and are not shown again in the edit form
(leave a secret field empty to keep the stored value). When you select a node in the editor, matching credentials are listed.

The realistic examples use these names — create them once and all examples work:

| Name | Type | How to get the values |
|---|---|---|
| `slack` | Slack | Slack → *Apps* → **Incoming Webhooks** → add to a channel → copy the `https://hooks.slack.com/services/…` URL into `webhookUrl` |
| `teams` | Microsoft Teams | Teams channel → *•••* → **Workflows** (or the Incoming Webhook connector) → create a webhook that posts to the channel → copy the URL into `webhookUrl` |
| `discord` | Discord | Discord channel → *Edit Channel* → **Integrations** → **Webhooks** → New Webhook → copy the `https://discord.com/api/webhooks/…` URL into `webhookUrl` |
| `smtp` | SMTP | Gmail: host `smtp.gmail.com`, port `587`, starttls `true`, username = your address, password = an **App password** (needs 2-step verification). Office 365: `smtp.office365.com`, 587. Local testing: Mailpit `localhost:1025`, starttls `false`. `from` e.g. `Shop <noreply@yourshop.com>` |
| `shop-db` | Database | JDBC URL + user/password of the shop database. Demo: `jdbc:h2:file:./data/miniflow`, user `sa`, empty password, after running `examples/sql/shop-schema.sql` (see §11) |
| `miniflow-db` | Database | MiniFlow's own DB for the execution report: `jdbc:h2:file:./data/miniflow`, `sa`, empty (or your PostgreSQL URL) |
| `openai` | AI | provider `openai`, `apiKey`, model (e.g. `gpt-4o-mini`). Also works with OpenAI-compatible APIs via `baseUrl` (Groq, OpenRouter, **Ollama**: `http://localhost:11434/v1`, any apiKey, model `llama3.1`). For Anthropic choose provider `anthropic` and a Claude model id |
| `github` | GitHub | Fine-grained token (github.com → Settings → Developer settings → Personal access tokens) with **Issues: Read and write** on the repos you use; for gists add the account permission **Gists: Read and write**. `apiBase` only for GitHub Enterprise (`https://ghe.example.com/api/v3`) |
| `google` | Google service account | Google Cloud console → enable **Google Sheets API** → IAM → Service accounts → create → Keys → **JSON**. Paste the whole JSON. Then **share the sheet** with the service account's email as *Editor* |
| `redis` | Redis | host/port/password/database; `ssl` true for managed Redis |
| `rabbitmq` | RabbitMQ | host, port 5672 (5671 with ssl), username, password, virtual host `/` |
| `kafka` | Kafka | `bootstrapServers` (`localhost:9092`), security protocol; for Confluent Cloud: `SASL_SSL`, mechanism `PLAIN`, API key/secret as username/password |
| *(any)* | HTTP header | `headerName` + `headerValue` (e.g. `Authorization` / `Bearer …`) for the generic `http` node |

The encryption key: `MINIFLOW_ENCRYPTION_KEY` (env) or `./data/encryption.key` (generated). **Back it up** — without it stored credentials cannot be decrypted.
