# log-friends-sdk

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JVM](https://img.shields.io/badge/JVM-21-007396.svg)](https://adoptium.net/)
[![Release](https://img.shields.io/badge/release-v1.0.0-2ea44f.svg)](https://github.com/log-freind/log-friends-sdk/tree/v1.0.0)

Log Friends SDK turns runtime activity inside a Spring Boot service into structured events that backend and data teams can understand together.

Instead of leaving business meaning in scattered string logs and separate documents, developers define an `eventName` and field descriptions in the code path where the event occurs. The SDK captures that event without requiring a data engineer to modify the target service, then sends it to Log Friends Console.

```text
Spring Boot App + log-friends-sdk
  -> ByteBuddy instrumentation
  -> startup Agent registration POST /api/agents
  -> Discovered LogEvent report after handshake
  -> HTTP JSON batch POST /ingest
  -> log-friends-console
```

The SDK also captures `HTTP`, `LOG`, `JDBC`, and `METHOD_TRACE` runtime signals. The current path is intentionally HTTP-only; it does not require Kafka, Spark, ClickHouse, or another broker.

## Why It Exists

Small teams often have useful data but cannot start using it because event names, payload meanings, and ownership are not agreed on. Log Friends moves that agreement closer to implementation:

```text
Code annotation
  -> structured LOG_EVENT
  -> bounded in-memory queue
  -> Console Raw Events
  -> Log Catalog contract review
```

The priority is target-service safety. Capture and delivery must not turn an observability tool into the reason the application fails.

## 1.0 Compatibility Baseline

Version `1.0.0` establishes the first stable public baseline for:

- annotation names and the `LOG_EVENT` payload shape
- `workerId`, `appName`, and ingest URL configuration keys
- startup Agent registration and discovered event reporting
- HTTP JSON batch delivery to Console `POST /ingest`
- bounded queue, batch interval, batch size, and drop behavior

Future breaking changes to these contracts require a new major version.

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
    implementation("com.github.log-freind:log-friends-sdk:v1.0.0")
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

The queue has three runtime boundaries:

- **time**: flush every `LOGFRIENDS_BATCH_INTERVAL_MS`
- **count**: flush when `LOGFRIENDS_BATCH_SIZE` is reached
- **capacity**: keep at most `LOGFRIENDS_QUEUE_CAPACITY` events in heap

When the queue is full, enqueue waits for at most 10 ms and then drops the new event. A failed HTTP batch is also dropped. This policy protects the main application from unbounded heap growth and Kubernetes `OOMKilled`; Log Friends events are observability data, not the source of truth for orders or payments.

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

To run the request-thread enqueue benchmark:

```bash
./gradlew test --tests com.logfriends.agent.transport.BatchTransporterBenchmarkTest
```

This benchmark measures local event construction and queue insertion only. It does not represent Console HTTP delivery or database write latency.

## Documentation

Detailed SDK docs live in the GitHub Wiki:

- [English Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki)
- [Korean Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/KO-Home)
- [Japanese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/JA-Home)
- [Simplified Chinese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/ZH-Home)
- [Spanish Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/ES-Home)
- [Portuguese Wiki Home](https://github.com/log-freind/log-friends-sdk/wiki/PT-Home)

Start there for runtime configuration, masking, `LOG_EVENT` contract details, package layout, and troubleshooting.
