# Log Friends Kotlin SDK

Spring Boot 서비스에 이벤트 이름과 필드 설명을 코드로 남기고, 실행 중 발생한 데이터를 Console로 보내는 SDK입니다.
데이터 엔지니어가 서비스 코드를 매번 추적하지 않고 이벤트의 의미와 실제 데이터를 함께 확인하도록 돕습니다.

```text
Spring Boot + SDK → HTTP JSON batch → Console → Log Catalog / Raw Events
```

## 처음 사용하기

JDK 21과 실행 중인 [Console](https://github.com/log-freind/log-friends-console)이 필요합니다.
예제를 먼저 실행하려면 [Examples](https://github.com/log-freind/log-friends-examples)를 사용하세요.

### 1. 의존성 추가

`build.gradle.kts`에 추가합니다. 아래 설치 예시는 태그 `1.1.0` 기준입니다.

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.log-freind:log-friends-sdk:1.1.0")
}
```

### 2. 실행 환경 설정

```bash
export LOGFRIENDS_INGEST_URL=http://localhost:8080/ingest
export LOGFRIENDS_WORKER_ID=product-service-local-1
export LOGFRIENDS_APP_NAME=product-service
export LOGFRIENDS_BATCH_SIZE=50
```

애플리케이션 JVM에 `-Djdk.attach.allowAttachSelf=true`를 전달하세요.
`workerId`는 실행 인스턴스의 식별자이고, `appName`은 같은 서비스를 묶는 이름입니다.

**현재 Console은 요청당 최대 50건을 받습니다. SDK 기본값은 100건이므로 위 설정을 유지하세요.**
DB 저장 배치 크기와 HTTP 전송 배치 크기는 별개입니다.

### 3. 이벤트 정의

Spring이 관리하는 서비스 메서드에 선언합니다.

```kotlin
import com.logfriends.agent.annotation.LogEvent
import com.logfriends.agent.annotation.LogField
import org.springframework.stereotype.Service

@Service
class ProductService {
    @LogEvent(name = "productViewed", description = "상품 상세 조회")
    fun view(
        @LogField(description = "상품 ID", type = "string")
        productId: String
    ): String = productId
}
```

메서드가 정상 반환하면 인자가 payload로 수집됩니다. 위 코드는 설명용 최소 예시이며,
이벤트 발생이 DB 트랜잭션 커밋 완료를 뜻하지는 않습니다.

## 수집 결과 확인

1. 시작 시 `POST /api/agents` 등록과 발견된 이벤트 정의 보고가 성공했는지 확인합니다.
2. 해당 메서드를 호출한 뒤 Console Web의 **Raw Events**에서 `productViewed`를 찾습니다.
3. **Log Catalog**에서 코드 설명과 실제 payload를 확인합니다.

코드에서 발견한 정의는 **힌트**입니다. 확정된 이벤트 계약인 **LogSpec**은 Console API에서 관리하며 자동 생성되지 않습니다.
이벤트 이름은 `productViewed`처럼 영문 소문자로 시작하는 camelCase를 사용하세요.

## 주요 설정

| 환경변수 | 기본값 / 의미 |
|---|---|
| `LOGFRIENDS_INGEST_URL` | Console의 `/ingest` 주소 |
| `LOGFRIENDS_WORKER_ID` | 인스턴스 식별자 |
| `LOGFRIENDS_APP_NAME` | 미설정 시 `logfriends.app.name`, `spring.application.name` 순으로 확인 |
| `LOGFRIENDS_BATCH_SIZE` | 100건. 현재 Console 연동에는 **50 이하** |
| `LOGFRIENDS_BATCH_INTERVAL_MS` | 500ms |
| `LOGFRIENDS_QUEUE_CAPACITY` | 10,000건 |
| `LOGFRIENDS_QUEUE_MEMORY_BUDGET_BYTES` | 33,554,432바이트(32MiB), 큐와 전송 중 이벤트의 추정 메모리 예산 |

배치 설정은 환경변수 또는 JVM 시스템 프로퍼티로 전달합니다.
일반 `application.yml`에 같은 키를 적는 것과 동일하지 않습니다.
정확한 키는 [BatchTransportConfig](src/main/kotlin/com/logfriends/agent/transport/BatchTransportConfig.kt)를 확인하세요.

## 동작과 한계

- ByteBuddy로 `HTTP`, `LOG`, `JDBC`, `METHOD_TRACE`, `LOG_EVENT`를 수집합니다.
- 요청 스레드에서 이벤트를 구성해 큐에 넣고, 별도 스케줄러 스레드가 HTTP로 전송합니다.
- 큐 개수나 추정 메모리 예산을 초과하면 신규 이벤트를 버립니다. 전송 실패에 대한 영구 저장이나 재시도 보장은 없습니다.
- 메모리 예산은 SDK 객체의 **추정치**이며 실제 JVM 힙이나 컨테이너 전체 메모리 제한이 아닙니다.
- SDK의 `sent`는 HTTP 성공 기준입니다. DB 저장 성공은 Console의 `stored`, `failed` 응답이나 저장 데이터로 확인해야 합니다.

결제 원장처럼 유실되면 안 되는 데이터의 저장 수단을 대체하지 않습니다.
개인정보는 수집 전에 제외하거나 `@LogMasked`로 마스킹하세요.

현재 작업 트리에는 JVM 전체 힙 사용률을 기준으로 수집을 제한하는 추가 기능이 있습니다.
`LOGFRIENDS_JVM_HEAP_MAX_USAGE_RATIO`(기본 0.85)와
`LOGFRIENDS_JVM_HEAP_CHECK_INTERVAL_MS`(기본 1000ms)를 사용합니다.
**태그 1.1.0의 제공 기능과 구분해야 하며**, 이 검사도 OOM 방지를 보장하지 않습니다.

## 개발 및 진단

```bash
./gradlew build
./gradlew publishToMavenLocal
```

테스트에서 계측이 필요 없으면 `logfriends.agent.enabled=false`로 비활성화합니다.
Spring Boot Actuator를 사용하는 앱은 `logfriends` endpoint를 노출해
`/actuator/logfriends`에서 큐, 전송, drop 통계를 확인할 수 있습니다.
진단 endpoint는 외부에 공개하지 마세요.

[Console](https://github.com/log-freind/log-friends-console) ·
[TypeScript SDK](https://github.com/log-freind/log-friends-ts-sdk) ·
[Examples](https://github.com/log-freind/log-friends-examples) · [Apache-2.0](LICENSE)
