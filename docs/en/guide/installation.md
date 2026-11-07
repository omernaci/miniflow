# Install and run

## Requirements
- Java 21 and Maven 3.9+
- Internet access from the browser (the editor loads Drawflow from the jsDelivr CDN)
- Optional: Docker (for PostgreSQL / Redis / RabbitMQ / Kafka on your machine)

## Start
```bash
mvn spring-boot:run          # http://localhost:8080
mvn test                     # runs the test suite (no external services needed)
```

On the first start, the MiniFlow monitoring workflows in src/main/resources/workflows (stats, error alert, weekly summary) 
are loaded; they need PostgreSQL and the miniflow-db / slack credentials. Data is kept in ./data/

## Build a jar
```bash
mvn -DskipTests package
java -jar target/miniflow-*.jar
```

## Use PostgreSQL instead of H2
```bash
cp .env .env          # set MINIFLOW_DB_PASSWORD and MINIFLOW_ENCRYPTION_KEY
docker compose up -d          # PostgreSQL 17 on localhost:5432
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```
In IntelliJ IDEA, set the active profile to `postgres` and add the variables from `.env` to the run configuration.
Tables are created automatically (`ddl-auto=update`).

To run MiniFlow itself in Docker as well: `docker compose --profile app up -d --build`.

## Configuration
Nothing secret is stored in the repository. Every setting comes from an environment variable
(defaults in `src/main/resources/application.properties`); `.env` lists them.

| Env variable | Default | Meaning |
|---|---|---|
| `MINIFLOW_PORT` | `8080` | HTTP port |
| `MINIFLOW_DB_URL` | H2 file `./data/miniflow` (PostgreSQL `localhost:5432/miniflow` with the `postgres` profile) | JDBC URL |
| `MINIFLOW_DB_USERNAME` | `sa` / `miniflow` | Database user |
| `MINIFLOW_DB_PASSWORD` | empty for H2, **required** for PostgreSQL | Database password |
| `MINIFLOW_ENCRYPTION_KEY` | generated in `./data/encryption.key` | Key used to encrypt credentials. Always set it outside development |
| `MINIFLOW_DEFAULT_LOCALE` | `en` | Default UI language (`en` / `tr`); also used for errors in scheduled runs |
| `MINIFLOW_EXECUTION_RETENTION_DAYS` | `14` | History kept this many days (cleanup every night at 03:00) |
| `MINIFLOW_SEED_EXAMPLES` | `true` | Load starter workflows into an empty database |
| `MINIFLOW_SEED_LOCATION` | `classpath:workflows/*.json` | Where the starter workflows are read from (e.g. `file:/opt/miniflow/seed/*.json`) |
| `MINIFLOW_H2_CONSOLE` | `false` | Enable the H2 console at `/h2-console` (development only) |
| `MINIFLOW_VARS_<NAME>` | — | Initial variables, e.g. `MINIFLOW_VARS_GITHUBTOKEN` (see [Variables](variables.md)) |

## Running the services the examples talk to (optional)
```bash
docker run -d --name redis    -p 6379:6379 redis:8
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management   # UI: localhost:15672 guest/guest
docker run -d --name kafka    -p 9092:9092 apache/kafka:4.1.0
```
For email testing without a real mailbox, [Mailpit](https://github.com/axllent/mailpit) is handy:
`docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit` → SMTP `localhost:1025` (starttls `false`), inbox at `localhost:8025`.
