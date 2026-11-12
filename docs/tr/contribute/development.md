# Geliştirme rehberi

## Derleme ve test

Gereksinimler: Java 21. Komutlar Maven Wrapper (`./mvnw`) kullanır; yerel Maven 3.9+ de çalışır.

```bash
./mvnw verify                  # derler ve tüm testleri çalıştırır (dış servis gerekmez)
./mvnw spring-boot:run         # ./data içindeki H2 veritabanıyla http://localhost:8080 adresinde başlatır
```

Testler bellek içi H2 veritabanı (`src/test/resources/application-test.properties`) ve HTTP, SMTP, GitHub, Google
ve AI sağlayıcıları için yerel sahte sunucular kullanır; gerçek servislere hiç istek atmaz.

## Proje yapısı

Kod, [Apache DolphinScheduler](https://github.com/apache/dolphinscheduler/tree/dev/dolphinscheduler-service/src/main/java/org/apache/dolphinscheduler/service)'a
benzer şekilde özelliklere göre paketlenmiştir.

```
com.omernaci.miniflow
├── MiniFlowApplication
├── config              MiniFlowProperties, dil yapılandırması
├── common
│   ├── exception       MiniFlowException (çevrilebilir hatalar)
│   └── i18n            Messages
├── workflow            Workflow modeli, WorkflowService (doğrulama, içe aktarma), JPA entity ve repository
├── engine              WorkflowEngine, ExecutionContext
│   └── expression      {{ }} ifade çözücü ve yardımcı fonksiyonlar
├── node                NodeExecutor SPI, NodeResult, ParamField, NodeTypeInfo
│   ├── support         Params, HttpSupport
│   ├── core            trigger, if, set, log, wait, dedupe
│   ├── http            http, rss
│   ├── data            database, spreadsheet
│   ├── notify          slack, email
│   ├── queue           kafka, rabbitmq, redis
│   ├── ai              ai (OpenAI uyumlu, Anthropic, Gemini)
│   ├── github          github
│   └── google          googleSheets
├── execution           ExecutionService, çalıştırma geçmişi
├── scheduler           cron zamanlamaları
├── queue               kuyruk bağlantıları ve dinleyiciler (kuyruk tetikleyicileri)
├── credential          kimlik bilgisi tipleri, AES-GCM şifreleme
├── variable            {{ vars.* }}
├── dashboard           panel istatistikleri
├── api                 REST API ve webhook'lar
└── web                 Thymeleaf controller'ları
    └── view            görünüm yardımcıları
```

## Yeni düğüm eklemek

`node` altındaki bir pakete `NodeExecutor` arayüzünü uygulayan bir Spring `@Component` yazın. Motor ve editör
paleti onu otomatik olarak tanır. Örnek kod için [İngilizce rehbere](../../en/contribute/development.md#adding-a-node) bakın.

- Dallanma: `NodeResult.branch(output, "true")` döndürün ve `outputs()` metodunu ezin.
- Etiketler: `messages.properties` ve `messages_tr.properties` dosyalarına `node.<tip>.label`, `node.<tip>.desc`
  ve `node.<tip>.field.<ad>` anahtarlarını ekleyin.
- Gizli bilgiler: `credentialTypes()` döndürün, `CredentialService.require(ad, "tip")` ile okuyun.
  Gizli bilgileri asla parametrelerden veya ifadelerden okumayın.
- `src/test/java` altına test ekleyin.

## Yeni kimlik bilgisi tipi

`CredentialType` enum'una alanlarıyla (`Field.required`, `Field.secret`, `Field.select`, …) bir sabit ekleyin;
mesaj dosyalarına `cred.type.<id>` / `cred.field.<id>.<alan>` etiketlerini yazın.

## Yeni dil

`messages.properties` dosyasını ör. `messages_de.properties` olarak kopyalayıp çevirin, `templates/fragments.html`
içine dil bağlantısını ekleyin.

## Kod stili

- Java 21, dört boşluk girinti, dosya başına bir sınıf.
- Kod, tanımlayıcılar, log mesajları ve yorumlar İngilizce. Kullanıcıya görünen metinler mesaj dosyalarına yazılır.
- Yorum yalnızca kodun kendini açıklayamadığı yerlerde; genişletme noktalarında (`NodeExecutor`, `ParamField`) Javadoc var.
- Kodda, testlerde ve örneklerde gizli bilgi olmaz. Yapılandırma ortam değişkenlerinden gelir (`.env`).

## Commit mesajları ve pull request'ler

- `[Modül] Kısa açıklama` biçimini kullanın, ör. `[Node] Add Microsoft Teams node`.
- Pull request'leri odaklı tutun; neyin değiştiğini ve nasıl test ettiğinizi yazın (PR şablonu).
- `./mvnw verify` geçmelidir.
