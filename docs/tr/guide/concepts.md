# Temel kavramlar

## Akış JSON'u
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
- `id`: küçük harf, rakam ve tire. İçe/dışa aktarma ve adreslerde anahtar budur.
- Her düğüm **önceki düğümün çıktısını** `input` olarak alır. Önceki tüm düğümlerin çıktısı `nodes['dugum-id']` ile okunur.
- Düğümler tetikleyiciden başlayarak sırayla çalışır. Bir düğüm hata verirse o dal durur ve çalıştırma **ERROR** olur.

## Tetikleyiciler
| Tetikleyici | Parametre | Ne zaman çalışır | `trigger` / `input` içeriği |
|---|---|---|---|
| Manuel | — | Editörde *Test et* veya `POST /api/workflows/{id}/run` | Test verisi / istek gövdesi |
| Webhook | `"path": "orders"` | `POST` veya `GET /webhook/orders` | JSON gövde, form alanları veya query parametreleri + `_headers` (küçük harf) |
| Zamanlama | `"cron": "*/10 * * * *"` | Cron zamanında | `{}` |
| Kuyruk | `"listen": { … }` | RabbitMQ / Kafka / Redis'ten gelen her mesajda | `{ "body": <mesaj>, … }` |

**Aktif anahtarı:** Webhook, zamanlama ve kuyruk dinleyici yalnızca **aktif** akışlarda çalışır.
İçe aktarılan örnekler pasif gelir: önce test edin, sonra aktifleştirin.

**Webhook yanıtı:** Son çalışan düğümün çıktısı döner: `{ "executionId": "…", "status": "SUCCESS", "data": { "ok": true } }`.
Hata olursa HTTP 500 ve `"data": {"error": "…"}`. Gövde JSON dizisiyse `{ "items": [...] }` olarak gelir.

**İmzalı webhook (GitHub tarzı):** Tetikleyiciye `"signatureSecretVar": "githubWebhookSecret"` ekleyin. Değişken tanımlıysa
`X-Hub-Signature-256` HMAC imzası doğrulanır, geçersiz istek 401 alır. Tanımlı değilse logda uyarı çıkar ve istek kabul edilir.

**Cron** 5 alanlıdır (dakika saat gün ay haftanın-günü), sunucunun saat dilimini kullanır; 6 alan yazarsanız ilki saniyedir.

| İfade | Anlamı |
|---|---|
| `*/5 * * * *` | 5 dakikada bir |
| `0 9 * * *` | her gün 09:00 |
| `45 7 * * MON-FRI` | hafta içi 07:45 |
| `0 0 1 * *` | her ayın 1'i gece yarısı |

**Kuyruk dinleyiciler:**
```json
"listen": { "type": "rabbitmq", "credential": "rabbitmq", "queue": "orders" }
"listen": { "type": "kafka", "credential": "kafka", "topic": "payments.events", "group": "grubum", "fromBeginning": false }
"listen": { "type": "redis", "credential": "redis", "list": "export-jobs" }      // iş kuyruğu (BLPOP), her mesajı tek tüketici alır
"listen": { "type": "redis", "credential": "redis", "channel": "events" }        // pub/sub, dinleyen herkes alır
```
- Mesaj `trigger.body` içinde gelir (JSON ise nesne): `{{ trigger.body.orderNo }}`.
- RabbitMQ: kuyruk yoksa kalıcı olarak oluşturulur; akış hata verse de mesaj onaylanır (sonsuz tekrar olmasın; hata Çalıştırmalar sayfasında).
- Kafka: varsayılan grup `miniflow-<akış id>`; `fromBeginning: true` değilse yeni mesajlardan başlar.
- Akış listesinde "⇄" etiketi, bağlanamazsa kırmızı **bağlanamadı** etiketi görünür (üzerine gelince sebep).
- Kimlik bilgisini değiştirince akışı bir kez pasif/aktif yapın ki yeniden bağlansın.

## Dedupe ("Yeni mi?")
Zamanlanmış akışlar aynı veriyi tekrar tekrar görür. `dedupe` bir anahtarı `seen_keys` tablosunda hatırlar: ilk kez → **new**, sonra → **seen**.
Sık kullanılan anahtarlar: `{{ input.id }}`, `order-{{ input.orderNo }}`, `incident-{{ now.substring(0, 13) }}` (= saatte bir).
Manuel testler yalnızca kontrol eder, kaydetmez.

## Döngü (Loop)

`loop` düğümü, **item** çıkışına bağlı düğümleri listedeki her eleman için bir kez çalıştırır, sonra **done**
çıkışından devam eder:

```json
{ "id": "each-order", "type": "loop", "params": { "items": "{{ orders.rows }}", "maxItems": 100 } },
{ "from": "each-order", "to": "notify", "branch": "item" },
{ "from": "each-order", "to": "report", "branch": "done" }
```

- item dalında `input` geçerli elemandır. Map'ler olduğu gibi, diğer değerler `{ "value": ... }` olarak gelir.
  `{{ loop.index }}` (0'dan), `{{ loop.number }}` (1'den), `{{ loop.total }}`, `{{ loop.first }}`,
  `{{ loop.last }}` ve `{{ loop.item }}` geçerli turu anlatır.
- **done** çıkışına `{ "items": [...], "count": n }` gelir: her turun son düğümünün çıktısı.
- Turlar sırayla çalışır. Bir eleman hata verirse döngü durur ve akış hata verir; eleman bazında hataları ele alıp
  devam etmek için item dalının içinde hata dalı kullanın.
- `maxItems` (varsayılan 100, en fazla 1000) beklenmedik büyüklükteki listelere karşı korur. Döngüler iç içe olabilir.
- Çalıştırma geçmişinde her turun adımları `#1`, `#2`, ... ile işaretlenir.
- `done` çıkışını `aggregate` düğümüyle özetleyin, ör. `mode: sum`, `field: total` → `{ result, count }`.

## Yeniden deneme (retry)

Tetikleyici dışındaki her düğüm, hata verince yeniden denenebilir. `params` alanının yanına bir `retry` nesnesi
ekleyin (veya düğüm panelinde **Hata olursa yeniden dene** bölümünü açın):

```json
{ "id": "call-api", "type": "http", "retry": { "times": 3, "delayMs": 1000, "backoff": 2.0 },
  "params": { "url": "https://api.example.com/orders" } }
```

| Alan | Varsayılan | Anlamı |
|---|---|---|
| `times` | — | İlk denemeden sonra en fazla kaç kez daha denenir, 0–5 |
| `delayMs` | `1000` | İlk tekrar öncesi bekleme, 0–30000 ms |
| `backoff` | `2.0` | Sonraki her beklemenin çarpanı, 1–10 (burada: 1 sn, 2 sn, 4 sn) |

Tek bir bekleme 30 saniyeyi geçmez. İfadeler her denemede yeniden çözülür ve her hata yeniden denenir.
Çalıştırma geçmişinde deneme sayısı görünür (↻ 3 deneme). Webhook'lar denemelerin bitmesini bekler; eşzamanlı
webhook akışlarında `times` ve `delayMs` değerlerini küçük tutun.

## Hata dalı

Varsayılan olarak hata veren bir düğüm akışı durdurur. `"onError": "branch"` ayarıyla (veya düğüm panelinde
*Hata çıkışından devam et* seçeneğiyle) düğüme ek bir `error` çıkışı eklenir ve akış oradan devam eder:

```json
{ "id": "call-api", "type": "http", "onError": "branch", "params": { "url": "https://api.example.com/orders" } },
{ "from": "call-api", "to": "notify-failure", "branch": "error" }
```

Hata çıkışına `error` (mesaj), `nodeId`, `type`, `attempts` ve `input` (hata veren düğümün aldığı veri) gelir;
ör. `{{ input.error }}` veya `{{ input.input.orderId }}`. Önce yeniden denemeler yapılır; hata dalına yalnızca son
denemeden sonra geçilir. Hata bu şekilde ele alındığında çalıştırma başarılı sayılır ve hatalı adım `→ error` ile
işaretlenir. `error` çıkışına hiçbir şey bağlı değilse akış eskisi gibi durur.

## Çalıştırma geçmişi
Her çalıştırma düğüm düğüm durum, dal, çıktı, hata ve süreyle saklanır. 20.000 karakteri aşan metinler (ör. dosya içeriği) geçmişte kısaltılır.
`Authorization` ve `Cookie` başlıkları asla yazılmaz. Geçmiş `execution-retention-days` gün sonra silinir.
