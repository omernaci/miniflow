# Core concepts

## Workflow JSON
```json
{
  "id": "order-flow",
  "name": "Order processing",
  "active": true,
  "nodes": [
    { "id": "start", "type": "trigger", "params": { "path": "orders", "sample": { "orderId": 42, "amount": 1500 } } },
    { "id": "check", "type": "if",      "params": { "condition": "{{ input.amount > 1000 }}" } },
    { "id": "big",   "type": "log",     "params": { "message": "LARGE order {{ input.orderId }}" } },
    { "id": "small", "type": "log",     "params": { "message": "Regular order {{ input.orderId }}" } }
  ],
  "connections": [
    { "from": "start", "to": "check" },
    { "from": "check", "to": "big",   "branch": "true" },
    { "from": "check", "to": "small", "branch": "false" }
  ]
}
```
- `id`: lowercase letters, digits and hyphens. It is the key used for import/export and the URL.
- Each node gets the **previous node's output** as `input`. Every node's output is also available later as `nodes['node-id']`.
- Nodes run breadth-first from the trigger. If a node fails, its branch stops and the execution is marked **ERROR**.

## Triggers
The first node (`type: "trigger"`) decides how the workflow starts:

| Trigger | Trigger params | Runs when | Data in `trigger` / `input` |
|---|---|---|---|
| Manual | — | *Test run* in the editor or `POST /api/workflows/{id}/run` | Test data / request body |
| Webhook | `"path": "orders"` | `POST` or `GET /webhook/orders` | JSON body, form fields or query params, plus `_headers` (lower-case) |
| Schedule | `"cron": "*/10 * * * *"` | On the cron schedule | `{}` |
| Queue | `"listen": { … }` | Each message from RabbitMQ / Kafka / Redis | `{ "body": <message>, … }` |

**Active switch:** webhooks, schedules and queue listeners run only for **active** workflows.
Imported examples come in inactive — test them, then activate.

**Webhook response:** the output of the last node that ran, e.g.
`{ "executionId": "…", "status": "SUCCESS", "data": { "ok": true } }`. On failure: HTTP 500 and `"data": {"error": "…"}`.
A JSON array body is wrapped as `{ "items": [...] }`.

**Signed webhooks (GitHub style):** add `"signatureSecretVar": "githubWebhookSecret"` to the trigger. If that variable is set,
the `X-Hub-Signature-256` HMAC is verified and invalid requests get 401. If it isn't set, a warning is logged and the call is accepted.

**Cron** uses 5 fields (minute hour day month weekday) in the server's time zone; 6 fields means seconds first.

| Expression | Meaning |
|---|---|
| `*/5 * * * *` | every 5 minutes |
| `0 9 * * *` | every day 09:00 |
| `45 7 * * MON-FRI` | weekdays 07:45 |
| `0 0 1 * *` | 1st of every month, midnight |

**Queue listeners**:
```json
"listen": { "type": "rabbitmq", "credential": "rabbitmq", "queue": "orders" }
"listen": { "type": "kafka", "credential": "kafka", "topic": "payments.events", "group": "my-group", "fromBeginning": false }
"listen": { "type": "redis", "credential": "redis", "list": "export-jobs" }      // work queue (BLPOP), one consumer per message
"listen": { "type": "redis", "credential": "redis", "channel": "events" }        // pub/sub, every listener gets it
```
- The message arrives as `trigger.body` (parsed if JSON): `{{ trigger.body.orderNo }}`.
- RabbitMQ: the queue is created (durable) if missing; messages are acknowledged even if the workflow fails (no poison-message loops — the error is visible on the Executions page).
- Kafka: default group `miniflow-<workflow id>`, starts from new messages unless `fromBeginning: true`.
- The Workflows list shows a "⇄" badge, or a red **not connected** badge (hover for the reason).
- After changing a credential, toggle the workflow inactive/active to reconnect.

## Dedupe ("Is it new?")
Scheduled workflows see the same data again and again. `dedupe` remembers a key in the `seen_keys` table: first time → **new**, after that → **seen**.
Keys like `{{ input.id }}`, `order-{{ input.orderNo }}` or `incident-{{ now.substring(0, 13) }}` (= once per hour) are common.
Manual test runs only check, they never record.

## Loop

The `loop` node runs the nodes connected to its **item** output once per list element, then continues on **done**:

```json
{ "id": "each-order", "type": "loop", "params": { "items": "{{ orders.rows }}", "maxItems": 100 } },
{ "from": "each-order", "to": "notify", "branch": "item" },
{ "from": "each-order", "to": "report", "branch": "done" }
```

- Inside the item branch `input` is the current element. Maps are passed as they are, other values as
  `{ "value": ... }`. `{{ loop.index }}` (from 0), `{{ loop.number }}` (from 1), `{{ loop.total }}`,
  `{{ loop.first }}`, `{{ loop.last }}` and `{{ loop.item }}` describe the current iteration.
- The **done** output receives `{ "items": [...], "count": n }`: the output of the last node of each iteration.
- Iterations run one after another. If an item fails, the loop stops and the workflow fails; use an error branch
  inside the item branch to handle failures per item and keep going.
- `maxItems` (default 100, at most 1000) protects against unexpectedly large lists. Loops can be nested.
- The execution history marks the steps of each iteration with `#1`, `#2`, ...
- Summarise the `done` output with the `aggregate` node, e.g. `mode: sum`, `field: total` → `{ result, count }`.

## Retry

Any node except the trigger can be retried when it fails. Add a `retry` object next to `params`
(or open **Retry on failure** in the node panel):

```json
{ "id": "call-api", "type": "http", "retry": { "times": 3, "delayMs": 1000, "backoff": 2.0 },
  "params": { "url": "https://api.example.com/orders" } }
```

| Field | Default | Meaning |
|---|---|---|
| `times` | — | Retries after the first attempt, 0–5 |
| `delayMs` | `1000` | Wait before the first retry, 0–30000 ms |
| `backoff` | `2.0` | Multiplier for each following wait, 1–10 (here: 1 s, 2 s, 4 s) |

A single wait never exceeds 30 seconds. Expressions are resolved again for every attempt and any error is retried.
The execution history shows the number of attempts (↻ 3 attempts). Webhooks wait for the retries, so keep
`times` and `delayMs` small on synchronous webhook workflows.

## Error branch

By default a failing node stops the workflow. Set `"onError": "branch"` (or choose *Continue on the error output*
in the node panel) to give the node an extra `error` output and continue there instead:

```json
{ "id": "call-api", "type": "http", "onError": "branch", "params": { "url": "https://api.example.com/orders" } },
{ "from": "call-api", "to": "notify-failure", "branch": "error" }
```

The error output receives `error` (message), `nodeId`, `type`, `attempts` and `input` (what the failed node received),
e.g. `{{ input.error }}` or `{{ input.input.orderId }}`. Retries run first; the error branch is taken only after the
last attempt. When the error is handled this way the execution counts as successful and the failed step is marked
with `→ error`. If nothing is connected to the `error` output, the workflow stops as before.

## Execution history
Every run is stored with each node's status, branch, output, error and duration. Long text values (> 20,000 characters, e.g. file contents) are truncated in history.
`Authorization` and `Cookie` headers are never stored. History is deleted after `execution-retention-days`.
