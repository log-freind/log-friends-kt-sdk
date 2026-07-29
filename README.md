# log-friends-sdk

Kotlin/JVM SDK for Spring Boot applications. It installs ByteBuddy instrumentation in the target JVM, captures runtime events, registers the running Agent with `log-friends-console`, and sends HTTP JSON event batches to the Console ingest endpoint.

```text
Spring Boot App + log-friends-sdk
  -> ByteBuddy instrumentation
  -> startup Agent registration POST /api/agents
  -> Discovered LogEvent report after handshake
  -> HTTP JSON batch POST /ingest
  -> log-friends-console
```

The SDK captures five event types with ByteBuddy: `HTTP`, `LOG`, `JDBC`, `METHOD_TRACE`, and `LOG_EVENT`. The current SDK path is HTTP-only; it does not assume Kafka, Spark, ClickHouse, or another broker/analytics pipeline.

## Repository Role

`log-friends-sdk` is only responsible for capture, startup registration, discovered `@LogEvent` hints, queueing, and HTTP delivery.

The surrounding repositories own the rest of the flow:

| Repository | Responsibility |
|---|---|
| `log-friends-console` | ingest API, Agent records, Raw Event storage, Log Catalog API, Raw Events API, stats |
| `log-friends-console-web` | Next.js UI for Log Catalog, Raw Events, filters, and CSV download |
| `log-friends-examples` | shopping mall demo app that generates realistic `LOG_EVENT` data |

Local full flow:

```text
log-friends-examples
  -> log-friends-sdk
  -> log-friends-console
  -> log-friends-console-web
```

## Quick Start

```kotlin
dependencies {
    implementation("com.github.log-freind:log-friends-sdk:v0.3.1")
}
```

Use JitPack for GitHub tag-based consumption:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

Required runtime configuration:

```bash
export LOGFRIENDS_INGEST_URL=http://localhost:8080/ingest
export LOGFRIENDS_WORKER_ID=order-service-local-1
export LOGFRIENDS_APP_NAME=order-service

# Optional: included in Discovered LogEvent reports when set.
export LOGFRIENDS_APP_VERSION=local

# Optional batch runtime policy.
export LOGFRIENDS_BATCH_SIZE=100
export LOGFRIENDS_BATCH_INTERVAL_MS=500
export LOGFRIENDS_QUEUE_CAPACITY=10000
```

Equivalent Spring/system properties:

- `LOGFRIENDS_INGEST_URL` or `logfriends.ingest.url`
- `LOGFRIENDS_WORKER_ID` or `logfriends.worker.id`
- `LOGFRIENDS_APP_NAME`, `logfriends.app.name`, or `spring.application.name`
- Optional `LOGFRIENDS_APP_VERSION` or `logfriends.app.version`
- Optional `LOGFRIENDS_BATCH_SIZE` or `logfriends.batch.size`
- Optional `LOGFRIENDS_BATCH_INTERVAL_MS` or `logfriends.batch.interval.ms`
- Optional `LOGFRIENDS_QUEUE_CAPACITY` or `logfriends.queue.capacity`

Environment variables take precedence over equivalent properties. `LOGFRIENDS_QUEUE_CAPACITY`
limits the number of queued events; it is a heap protection boundary, not a byte or MB limit.

Required JVM option for runtime attach:

```bash
-Djdk.attach.allowAttachSelf=true
```

## Runtime Behavior

At Spring Boot startup, the SDK installs ByteBuddy instrumentation for:

- `HTTP`: Spring MVC request handling
- `LOG`: Logback append events
- `JDBC`: `PreparedStatement` executions
- `METHOD_TRACE`: public `@Service` methods
- `LOG_EVENT`: methods annotated with `@LogEvent`

On `ApplicationReadyEvent`, the SDK sends startup Agent registration to Console `POST /api/agents` using `workerId` and `appName`. If registration succeeds, the SDK scans loaded classes for `@LogEvent` methods and reports Discovered LogEvent candidates to `POST /api/agents/{agentId}/discovered-log-events`.

Runtime events are queued and flushed as HTTP JSON batches to the configured `LOGFRIENDS_INGEST_URL`, normally Console `POST /ingest`. `/ingest` stores captured Raw Events only; it does not auto-register Agents.

Discovered LogEvent candidates are code hints, not contracts. The SDK does not auto-register or promote `LogSpec`; create and edit `LogSpec` through Console APIs.

## LogEvent Example

Business `LOG_EVENT` names must use camelCase. `@LogField` metadata is included in Discovered LogEvent hints:

```kotlin
import com.logfriends.agent.annotation.LogEvent
import com.logfriends.agent.annotation.LogField

@LogEvent(
    name = "userRegistered",
    description = "User registration business event",
    apiMethod = "POST",
    apiPath = "/users"
)
fun registerUser(
    @LogField(description = "Registered user identifier", type = "STRING")
    userId: String
)
```

## Build

```bash
./gradlew build
./gradlew publishToMavenLocal
```

## Documentation

Detailed SDK docs live in the GitHub Wiki:

- [English Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki)
- [Korean Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/KO-Home)
- [Japanese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/JA-Home)
- [Simplified Chinese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/ZH-Home)
- [Spanish Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/ES-Home)
- [Portuguese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/PT-Home)

Start there for runtime configuration, masking, `LOG_EVENT` contract details, package layout, and troubleshooting.
