# Düğüm referansı

| Düğüm | Ne yapar | Ana parametreler | Çıktı |
|---|---|---|---|
| `trigger` | Akışı başlatır | `path`, `cron`, `listen`, `sample`, `signatureSecretVar` | tetikleyici verisi |
| `set` | Alan ekler / değiştirir | `values`, `keepInput` (varsayılan true) | girdi + values |
| `if` | true/false dallanma | `condition` | girdi, dal `true`/`false` |
| `loop` | `item` dalını listedeki her eleman için çalıştırır | `items`, `maxItems` (varsayılan 100) | `done`: `items` (her turun son çıktısı), `count` |
| `aggregate` | Bir listeyi özetler | `items`, `mode` (collect, count, sum, avg, min, max, groupBy, join, unique), `field` (noktalı yol), `separator` | `result`, `count` (groupBy için ayrıca `counts`) |
| `dedupe` | new/seen dallanma | `key` | girdi, dal `new`/`seen` |
| `wait` | Bekler | `seconds` (0–300) | girdi |
| `http` | HTTP isteği | `method`, `url`, `headers`, `body`, `credential` (http-header), `failOnError` | `status`, `ok`, `body` (`failOnError:false` ve çağrı başarısızsa `error`) |
| `log` | Uygulama loguna yazar | `message` | girdi + `logged` |
| `respond` | Webhook HTTP yanıtını belirler | `status`, `headers`, `body` | yanıtın kendisi |
| `slack` | Kanala mesaj | `credential`, `text` | `sent`, `status` |
| `email` | SMTP ile gönderir | `credential`, `to`, `cc`, `subject`, `text`, `html` (bool), `attachments` | `sent` |
| `database` | SQL çalıştırır | `credential`, `query`, `params`, `maxRows` | SELECT: `rows`, `count`, `first`; diğer: `updated` |
| `spreadsheet` | CSV / XLSX üretir | `rows`, `format` (xlsx/csv), `fileName`, `columns`, `sheetName`, `delimiter` | `file`, `rowCount`, `columns` |
| `file` | Yerel dökümanları listele / oku | `operation` (list/read), `path`, `pattern`, `recursive`, `pages` (PDF), `maxChars` | list: `files`, `count`; read: `text`, `pages`, `chars`, `truncated` |
| `rss` | RSS/Atom okur | `url`, `limit` | `title`, `items`, `latest` |
| `ai` | LLM'e sorar | `credential`, `prompt`, `system`, `maxTokens`, `temperature`, `json` | `text`, `data` (`json:true` ise), `usage` |
| `github` | Issue ve gist işlemleri | `credential`, `operation` (createIssue, comment, addLabels, close, createGist), `repo`, `title`, `body`, `labels`, `issueNumber`; gist: `fileName`, `content`, `description`, `public` | `number`, `url`; gist: `id`, `url`, `rawUrl` |
| `googleSheets` | Satır ekle / oku | `credential`, `operation` (append/read), `spreadsheetId`, `range`, `values`, `columns` | append: `updatedRange`; read: `rows` |
| `redis` | Kuyruk / pub-sub / anahtar | `credential`, `operation` (rpush, lpush, publish, set, get, incr, del), `key`/`channel`, `message`, `ttlSeconds` | işleme göre (`length`, `value`, …) |
| `rabbitmq` | Mesaj yayınlar (onaylı) | `credential`, `exchange`, `routingKey`, `message`, `declareQueue` | `messageId` |
| `kafka` | Mesaj üretir | `credential`, `topic`, `key`, `message` | `partition`, `offset` |

Notlar:
- **SQL:** Değerleri sorguya yapıştırmayın, isimli parametre kullanın: `"query": "… WHERE email = :email", "params": {"email": "{{ input.email }}"}`. Sütun adları küçük harfle gelir (`{{ input.first.order_no }}`). H2, PostgreSQL ve MySQL sürücüleri hazır.
- **Dosyalar:** `spreadsheet` çıktısında `file` (`name`, `mimeType`, `size`, base64 `data`) var. E-postaya ek: `"attachments": "{{ input.file }}"` (veya liste). Türkçe Excel'de CSV için `"delimiter": ";"` önerilir; UTF-8 BOM otomatik eklenir.
- **Yerel dökümanlar:** `file` yalnızca `MINIFLOW_FILES_DIR` ile verilen klasörde çalışır (yollar bu klasöre göredir; `..` ve klasör dışına çıkan linkler reddedilir). `read` PDF, Word `.docx` ve metin dosyalarını (txt, md, csv, json, html, xml, yml, log) okur. `maxChars` değerinden uzun metin kesilir ve `truncated` true olur; uzun PDF'lerde `"pages": "1-5"` ile sayfa aralığı okuyun. Gizli dosyalar `list` sonucunda yer almaz.
- **Gist:** `content` metin veya satır listesi olabilir. Satırlar `.md` için Markdown tablo, `.csv` için CSV, diğerlerinde biçimli JSON olur — yani `"content": "{{ nodes['query'].rows }}"` ve `"fileName": "report.md"` yeterli. `"public": true` verilmedikçe gist gizlidir (gizli gist listelenmez ama özel değildir: bağlantıyı bilen okuyabilir).
- **Yapay zekâ `json: true`:** `system` içinde tam JSON biçimini tarif edin; çözülmüş nesne `data` alanındadır.
- **HTTP ile izleme:** `"failOnError": false` ile 4xx/5xx *ve* bağlantı hataları akışı durdurmaz; `{{ !input.ok }}` ile kontrol edin.
- **Webhook yanıtı:** `respond` düğümü yoksa webhook son düğümün çıktısını 200 durumuyla döndürür. `status`, `headers` ve `body` değerlerini kendiniz seçmek için bir `respond` düğümü ekleyin, ör. `{"status": 201, "headers": {"Location": "/orders/{{ input.id }}"}, "body": "{{ input }}"}`. JSON `body` JSON olarak, metin ise düz metin olarak gönderilir. Başarılı son `respond` düğümü geçerlidir.
- Hata mesajlarına URL yazılmaz; webhook adresleri ve token'lar geçmişe sızmaz.
