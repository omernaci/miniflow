# Node reference

| Node | What it does | Main parameters | Output |
|---|---|---|---|
| `trigger` | Starts the workflow | `path`, `cron`, `listen`, `sample`, `signatureSecretVar` | trigger data |
| `set` | Add / change fields | `values`, `keepInput` (default true) | input + values |
| `if` | Branch true/false | `condition` | input, branch `true`/`false` |
| `loop` | Run the `item` branch once per list element | `items`, `maxItems` (default 100) | `done`: `items` (last output of each iteration), `count` |
| `aggregate` | Summarise a list | `items`, `mode` (collect, count, sum, avg, min, max, groupBy, join, unique), `field` (dotted path), `separator` | `result`, `count` (+`counts` for groupBy) |
| `dedupe` | Branch new/seen | `key` | input, branch `new`/`seen` |
| `wait` | Pause | `seconds` (0–300) | input |
| `http` | HTTP request | `method`, `url`, `headers`, `body`, `credential` (http-header), `failOnError` | `status`, `ok`, `body` (+`error` when `failOnError:false` and the call failed) |
| `log` | Write to application log | `message` | input + `logged` |
| `respond` | Set the webhook HTTP response | `status`, `headers`, `body` | the response itself |
| `slack` | Post to a channel | `credential`, `text` | `sent`, `status` |
| `email` | Send via SMTP | `credential`, `to`, `cc`, `subject`, `text`, `html` (bool), `attachments` | `sent` |
| `database` | Run SQL | `credential`, `query`, `params`, `maxRows` | SELECT: `rows`, `count`, `first`; other: `updated` |
| `spreadsheet` | Build CSV / XLSX | `rows`, `format` (xlsx/csv), `fileName`, `columns`, `sheetName`, `delimiter` | `file`, `rowCount`, `columns` |
| `file` | List / read local documents | `operation` (list/read), `path`, `pattern`, `recursive`, `pages` (PDF), `maxChars` | list: `files`, `count`; read: `text`, `pages`, `chars`, `truncated` |
| `rss` | Read RSS/Atom | `url`, `limit` | `title`, `items`, `latest` |
| `ai` | Ask an LLM | `credential`, `prompt`, `system`, `maxTokens`, `temperature`, `json` | `text`, `data` (when `json:true`), `usage` |
| `github` | Issues and gists | `credential`, `operation` (createIssue, comment, addLabels, close, createGist), `repo`, `title`, `body`, `labels`, `issueNumber`; gist: `fileName`, `content`, `description`, `public` | `number`, `url`; gist: `id`, `url`, `rawUrl` |
| `googleSheets` | Append / read rows | `credential`, `operation` (append/read), `spreadsheetId`, `range`, `values`, `columns` | append: `updatedRange`; read: `rows` |
| `redis` | Queue / pub-sub / keys | `credential`, `operation` (rpush, lpush, publish, set, get, incr, del), `key`/`channel`, `message`, `ttlSeconds` | depends on operation (`length`, `value`, …) |
| `rabbitmq` | Publish (with confirms) | `credential`, `exchange`, `routingKey`, `message`, `declareQueue` | `messageId` |
| `kafka` | Produce | `credential`, `topic`, `key`, `message` | `partition`, `offset` |

Notes:
- **SQL:** never paste values into the query — use named parameters: `"query": "… WHERE email = :email", "params": {"email": "{{ input.email }}"}`. Column names come back lower case (`{{ input.first.order_no }}`). H2, PostgreSQL and MySQL drivers are included.
- **Files:** `spreadsheet` returns `file` (`name`, `mimeType`, `size`, base64 `data`). Pass it to email: `"attachments": "{{ input.file }}"` (or a list). For CSV in Excel with a non-English locale, `"delimiter": ";"` helps; a UTF-8 BOM is added.
- **Local documents:** `file` only works inside the folder set by `MINIFLOW_FILES_DIR` (paths are relative to it; `..` and links out of it are rejected). `read` supports PDF, Word `.docx` and text files (txt, md, csv, json, html, xml, yml, log). Text longer than `maxChars` is cut and `truncated` is true; for long PDFs read a page range with `"pages": "1-5"`. Hidden files are skipped by `list`.
- **Gists:** `content` can be text or a list of rows. Rows become a Markdown table for `.md`, CSV for `.csv`, pretty JSON otherwise — so `"content": "{{ nodes['query'].rows }}"` with `"fileName": "report.md"` just works. Gists are secret unless `"public": true` (secret gists are unlisted, not private: anyone with the link can read them).
- **AI with `json: true`:** describe the exact JSON shape in `system`; the parsed object is in `data`.
- **HTTP monitoring:** use `"failOnError": false` so 4xx/5xx *and* connection errors don't stop the flow; check `{{ !input.ok }}`.
- **Webhook response:** without a `respond` node a webhook returns the last node's output with status 200. Add a `respond` node to choose the `status`, `headers` and `body` yourself, e.g. `{"status": 201, "headers": {"Location": "/orders/{{ input.id }}"}, "body": "{{ input }}"}`. A JSON `body` is sent as JSON, a string as text. The last successful `respond` node wins.
- Error messages never include URLs, so webhook URLs and tokens don't leak into history.
