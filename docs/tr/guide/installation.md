# Kurulum ve çalıştırma

## Gereksinimler
- Java 21 (Maven zorunlu değil; proje Maven Wrapper ile gelir, komutlar `./mvnw` kullanır)
- Tarayıcının internete erişimi (editör Drawflow'u jsDelivr CDN'den yükler)
- İsteğe bağlı: Docker (PostgreSQL / Redis / RabbitMQ / Kafka için)

## Başlatma
```bash
./mvnw spring-boot:run        # http://localhost:8080
./mvnw test                  # testler (dış servis gerekmez)
```

İlk açılışta src/main/resources/workflows içindeki MiniFlow izleme akışları (istatistik, hata alarmı, haftalık özet) 
yüklenir; bunlar PostgreSQL ve miniflow-db / slack kimlik bilgilerini ister. Veriler ./data/

## Jar oluşturma
```bash
./mvnw -DskipTests package
java -jar target/miniflow-*.jar
```

## H2 yerine PostgreSQL
```bash
cp .env .env          # MINIFLOW_DB_PASSWORD ve MINIFLOW_ENCRYPTION_KEY değerlerini girin
docker compose up -d          # localhost:5432'de PostgreSQL 17
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```
IntelliJ IDEA'da aktif profili `postgres` yapın ve `.env` içindeki değişkenleri çalıştırma yapılandırmasına ekleyin.
Tablolar otomatik oluşur (`ddl-auto=update`).

MiniFlow'u da Docker'da çalıştırmak için: `docker compose --profile app up -d --build`.

## Yapılandırma
Depoda hiçbir gizli bilgi tutulmaz. Tüm ayarlar ortam değişkenlerinden gelir
(varsayılanlar `src/main/resources/application.properties` içinde); liste için `.env` dosyasına bakın.

| Ortam değişkeni | Varsayılan | Anlamı |
|---|---|---|
| `MINIFLOW_PORT` | `8080` | HTTP portu |
| `MINIFLOW_DB_URL` | H2 dosyası `./data/miniflow` (`postgres` profilinde `localhost:5432/miniflow`) | JDBC adresi |
| `MINIFLOW_DB_USERNAME` | `sa` / `miniflow` | Veritabanı kullanıcısı |
| `MINIFLOW_DB_PASSWORD` | H2 için boş, PostgreSQL için **zorunlu** | Veritabanı şifresi |
| `MINIFLOW_ENCRYPTION_KEY` | `./data/encryption.key` içinde üretilir | Kimlik bilgilerini şifreleyen anahtar. Geliştirme dışında mutlaka verin |
| `MINIFLOW_DEFAULT_LOCALE` | `en` | Varsayılan arayüz dili (`en` / `tr`); zamanlanmış çalıştırmalardaki hata mesajları da bu dilde |
| `MINIFLOW_EXECUTION_RETENTION_DAYS` | `14` | Geçmiş kaç gün tutulur (her gece 03:00'te temizlik) |
| `MINIFLOW_SEED_EXAMPLES` | `true` | Boş veritabanına başlangıç akışlarını yükle |
| `MINIFLOW_SEED_LOCATION` | `classpath:workflows/*.json` | Başlangıç akışlarının okunduğu yer (ör. `file:/opt/miniflow/seed/*.json`) |
| `MINIFLOW_FILES_DIR` | — | File node'unun listeleyip okuyabileceği klasör. Boşsa File node kapalıdır |
| `MINIFLOW_H2_CONSOLE` | `false` | `/h2-console` adresinde H2 konsolunu açar (yalnızca geliştirme) |
| `MINIFLOW_VARS_<AD>` | — | Başlangıç değişkenleri, ör. `MINIFLOW_VARS_GITHUBTOKEN` ([Değişkenler](variables.md)) |

Arayüzü Türkçe açmak için: `MINIFLOW_DEFAULT_LOCALE=tr ./mvnw spring-boot:run` (veya sağ üstten **TR**).

## Örneklerin konuştuğu servisleri yerelde çalıştırmak (isteğe bağlı)
```bash
docker run -d --name redis    -p 6379:6379 redis:8
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management   # arayüz: localhost:15672 guest/guest
docker run -d --name kafka    -p 9092:9092 apache/kafka:4.1.0
```
Gerçek posta kutusu olmadan e-posta denemek için [Mailpit](https://github.com/axllent/mailpit):
`docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit` → SMTP `localhost:1025` (starttls `false`), gelen kutusu `localhost:8025`.
