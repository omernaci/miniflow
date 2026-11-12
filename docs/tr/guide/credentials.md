# Kimlik bilgileri: servis servis kurulum

Gizli bilgiler **Kimlik bilgileri** sayfasında AES-256-GCM ile şifreli saklanır ve adıyla kullanılır: `"credential": "slack"`.
İfadelerle okunamaz, dışa aktarılan JSON'a girmez, düzenleme formunda tekrar gösterilmez
(gizli alanı boş bırakırsanız kayıtlı değer korunur). Editörde düğüm seçince uygun kimlik bilgileri listelenir.

Gerçekçi örnekler şu adları kullanır — bir kez oluşturun, tüm örnekler çalışsın:

| Ad | Tip | Değerler nereden alınır |
|---|---|---|
| `slack` | Slack | Slack → *Apps* → **Incoming Webhooks** → kanala ekleyin → `https://hooks.slack.com/services/…` adresini `webhookUrl` alanına |
| `teams` | Microsoft Teams | Teams kanalı → *•••* → **Workflows** (ya da Incoming Webhook bağlayıcısı) → kanala gönderen bir webhook oluşturun → adresi `webhookUrl` alanına |
| `discord` | Discord | Discord kanalı → *Edit Channel* → **Integrations** → **Webhooks** → New Webhook → `https://discord.com/api/webhooks/…` adresini `webhookUrl` alanına |
| `smtp` | SMTP | Gmail: `smtp.gmail.com`, port `587`, starttls `true`, kullanıcı = adresiniz, şifre = **Uygulama şifresi** (2 adımlı doğrulama gerekir). Office 365: `smtp.office365.com`, 587. Yerel test: Mailpit `localhost:1025`, starttls `false`. `from` ör. `Shop <noreply@magazam.com>` |
| `shop-db` | Veritabanı | Mağaza veritabanının JDBC URL'si + kullanıcı/şifre. Demo: `jdbc:h2:file:./data/miniflow`, kullanıcı `sa`, şifre boş; önce `examples/sql/shop-schema.sql` çalıştırılır (bkz. §11) |
| `miniflow-db` | Veritabanı | Çalıştırma raporu için MiniFlow'un kendi veritabanı: `jdbc:h2:file:./data/miniflow`, `sa`, boş (veya PostgreSQL adresiniz) |
| `openai` | Yapay zekâ | provider `openai`, `apiKey`, model (ör. `gpt-4o-mini`). `baseUrl` ile OpenAI uyumlu servisler de olur (Groq, OpenRouter, **Ollama**: `http://localhost:11434/v1`, apiKey herhangi, model `llama3.1`). Anthropic için provider `anthropic` ve bir Claude model id'si |
| `github` | GitHub | Fine-grained token (github.com → Settings → Developer settings → Personal access tokens), kullandığınız depolarda **Issues: Read and write**; gist için hesap izni **Gists: Read and write**. `apiBase` yalnızca GitHub Enterprise için (`https://ghe.ornek.com/api/v3`) |
| `google` | Google servis hesabı | Google Cloud console → **Google Sheets API**'yi etkinleştirin → IAM → Service accounts → oluştur → Keys → **JSON**. JSON'un tamamını yapıştırın. Sonra **tabloyu** servis hesabının e-postasıyla *Düzenleyen* olarak paylaşın |
| `redis` | Redis | host/port/şifre/veritabanı; yönetilen Redis'te `ssl` true |
| `rabbitmq` | RabbitMQ | host, port 5672 (ssl ile 5671), kullanıcı, şifre, virtual host `/` |
| `kafka` | Kafka | `bootstrapServers` (`localhost:9092`), güvenlik protokolü; Confluent Cloud: `SASL_SSL`, mekanizma `PLAIN`, API key/secret kullanıcı/şifre olarak |
| *(herhangi)* | HTTP başlığı | Genel `http` düğümü için `headerName` + `headerValue` (ör. `Authorization` / `Bearer …`) |

Şifreleme anahtarı: `MINIFLOW_ENCRYPTION_KEY` (ortam) veya `./data/encryption.key` (otomatik). **Yedekleyin** — anahtar olmadan kayıtlı kimlik bilgileri çözülemez.
