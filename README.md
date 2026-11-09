# Log Friends Kotlin SDK

Spring Boot 앱에서 HTTP·로그·JDBC·메서드 실행과 비즈니스 이벤트를 수집해 Console로 보내는 Kotlin/JVM SDK입니다.
`@LogEvent`로 이벤트 이름을, `@LogField`로 필드 설명을 코드에 남길 수 있습니다.

```text
Spring Boot + SDK -> HTTP JSON batch POST /ingest -> Console -> TimescaleDB
                                                            -> Raw Events / Log Catalog
```

SDK는 수집·전송을, Console은 Agent 상태·검증·저장·조회·통계를 담당합니다. 별도 메시지 브로커는 사용하지 않습니다.
바로 실행해 보려면 [Examples](https://github.com/log-freind/log-friends-examples)를 참고하세요.

> **개발 구현 기준 문서:** 최신 로컬 코드의 사용법을 설명합니다. 이 README 전용 PR에는 제품 코드·배포 변경이 포함되지 않습니다. GitHub 기본 브랜치만 clone한 경우 아래 기능·명령이 아직 일치하지 않을 수 있으며, 관련 코드 반영 후 사용해야 합니다.

## 연결 순서

### 1. Console에서 앱과 키 준비

[Console](https://github.com/log-freind/log-friends-console)을 실행하고 **로그인 -> 앱 등록 -> 서버 수집 키 발급** 순서로 준비합니다.
앱 이름·고정 Worker ID·앱 버전은 Console에서 설정합니다. 키 하나는 Worker 하나에 대응하므로 여러 인스턴스에서 같은 키를 동시에 사용하지 마세요.

### 2. 현재 SDK 설치

현재 소스 기준은 **JDK 21, Spring Boot 4.0.8, SDK `1.1.1`**입니다. Boot 3 호환은 보장하지 않습니다.
아래 명령은 공개 태그가 아닌 해당 개발 소스가 있는 SDK 저장소에서 실행합니다.

```bash
./gradlew build
./gradlew publishToMavenLocal
```

사용할 앱의 `build.gradle.kts`에 추가합니다.

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("com.logfriends:log-friends-sdk:1.1.1")
}
```

**호환성 주의:** 현재 개발 소스는 공개 모델 20개를 기능별 `model` 패키지로 이동했습니다. 이전 import·바이너리 호환을 보장하지 않으므로 import 수정과 재컴파일이 필요합니다. 대응표는 로컬 소스의 `src/test/resources/architecture/public-sdk-type-migrations.properties`입니다. 같은 `1.1.1` 로컬 캐시도 다시 설치해야 하며, 과거 JitPack 아티팩트와 구분해야 합니다.

### 3. 환경변수와 JVM 옵션 지정

```bash
export LOGFRIENDS_APP_KEY='<issued server key>'
export LOGFRIENDS_ENV=test
```

앱 JVM에 `-Djdk.attach.allowAttachSelf=true`를 전달하세요. 원문 키는 실행 환경에만 주입하고 소스·브라우저 코드·Git에 넣지 마세요.

| 환경 | Console 기본 주소 |
|---|---|
| `test` (기본) | `http://localhost:8082/logfriend` |
| `nas` | `http://log-friends-console:8080/logfriend` (같은 Kubernetes namespace) |

SDK는 시작 시 앱 키를 `X-LogFriends-App-Key` 헤더로 보내 `GET /api/sdk/config`를 조회합니다.
앱 이름·Worker·버전은 이 응답을 사용하며, 개별 `LOGFRIENDS_INGEST_URL`·`LOGFRIENDS_WORKER_ID`·`LOGFRIENDS_APP_NAME`·`LOGFRIENDS_APP_VERSION`은 정상 시작 경로의 설정이 아닙니다.
Console 설정 변경은 앱 재시작 후 반영됩니다. 설정 조회가 실패하면 앱 기동을 막지 않고 SDK만 비활성화하므로, 연결을 복구한 뒤 재시작해야 합니다. `test`·`nas` 외 환경 주소는 구성되어 있지 않습니다.

### 4. 이벤트 선언과 확인

Spring 서비스 메서드에 선언하고 실제로 호출합니다.

```kotlin
import com.logfriends.agent.annotation.LogEvent
import com.logfriends.agent.annotation.LogField
import org.springframework.stereotype.Service

@Service
class ProductService {
    @LogEvent(name = "productViewed", description = "상품 상세 조회")
    fun view(
        @LogField(description = "상품 ID", type = "STRING")
        productId: String
    ): String = productId
}
```

정상 반환한 메서드의 **인자**가 payload가 됩니다. 이벤트가 DB 트랜잭션 커밋을 보장하지는 않습니다.
이벤트 이름은 `productViewed`처럼 소문자로 시작하는 camelCase를 사용합니다. 인자 이름 메타데이터가 없으면 `arg0` 등의 대체 이름을 사용합니다.

시작 등록 성공을 확인한 뒤 Console Web의 **Raw Events**에서 앱과 `eventName=productViewed`로 조회하세요.
**Log Catalog**에서는 코드 설명과 실제 payload를 비교할 수 있습니다. SDK가 보고한 발견 정의는 힌트이며, 확정 계약인 LogSpec의 등록·수정은 Console API 책임입니다. `/ingest`가 Agent나 LogSpec을 자동 등록하지는 않습니다.

## 등록과 수집 정책

- 시작 시 `POST /api/agents`로 등록하고, 등록 시도 후 60초 간격으로 `POST /api/agents/heartbeat`를 보냅니다. 최초 등록 실패는 다음 주기에 등록부터 재시도합니다. 정기 heartbeat는 metadata를 생략해 시작 API·힙 스냅샷을 보존하며 Spring 컨텍스트 종료 시 스케줄을 닫습니다.
- API 수집 정책은 등록 직후와 heartbeat 성공 후 조회·적용하고 적용 버전을 ACK합니다. Console에서 바꾼 정책은 재시작 없이 다음 갱신에 반영됩니다.
- OFF인 동기 Spring MVC 요청은 `HTTP`·`LOG`·`JDBC`·`METHOD_TRACE`·`LOG_EVENT` 모두 큐에 넣지 않습니다. 최초 정책 수신 전에도 해당 요청 수집을 차단하며, 갱신 실패 시 마지막 정책을 유지합니다. 비동기 스레드로의 정책 전파와 요청·응답 본문 전체 수집은 포함하지 않습니다.

## 전송과 저장은 다릅니다

SDK 기본 배치는 **50건 / 500ms**이며, Console도 HTTP 요청당 최대 50건을 받습니다. 배치 크기를 크게 지정해도 50으로 제한하고 전송 요청도 최대 50건으로 나눕니다. Console의 DB 저장 배치 크기와는 별개입니다.

| SDK 통계 | 의미 |
|---|---|
| `sent` | HTTP 전송 성공. 저장 완료와 다름 |
| `stored` / `storageFailed` | Console 응답으로 확인한 원본 저장 / 검증·저장 실패 |
| `duplicates` / `unconfirmed` | 중복으로 분류된 건수 / 응답 누락·불일치로 저장 결과를 확인하지 못한 건수 |
| `dropped` | 큐·메모리 제한 또는 HTTP 오류·연결 실패 등으로 SDK가 버린 건수 |

`stored` 등은 `sent`의 결과 분류이므로 더해서 합산하지 않습니다. HTTP 성공 후 결과를 알 수 없어도 자동 재전송하지 않습니다.
큐는 메모리에만 있고 영구 보관·전송 재시도·무손실 보장이 없습니다. 결제 원장 등의 저장 수단을 대체하지 않습니다.
개인정보는 수집 전에 제외하거나 `@LogMasked`를 적용하세요.

## 설정과 개발

| 환경변수 | 기본값 |
|---|---|
| `LOGFRIENDS_BATCH_SIZE` | 50건 (최대 50) |
| `LOGFRIENDS_BATCH_INTERVAL_MS` | 500ms |
| `LOGFRIENDS_QUEUE_CAPACITY` | 10,000건 |
| `LOGFRIENDS_QUEUE_MEMORY_BUDGET_BYTES` | 33,554,432바이트 (32MiB) |
| `LOGFRIENDS_JVM_HEAP_MAX_USAGE_RATIO` | 0.85 |
| `LOGFRIENDS_JVM_HEAP_CHECK_INTERVAL_MS` | 1,000ms |

전송 설정은 환경변수 또는 JVM 시스템 프로퍼티로 읽습니다. 일반 `application.yml` 설정과 동일하지 않습니다. 정확한 프로퍼티 이름은 로컬 소스의 `src/main/kotlin/com/logfriends/agent/transport/config/BatchTransportConfig.kt`에서 확인할 수 있습니다.
큐·전송 중 객체의 메모리는 추정치이며, JVM 힙 사용률 검사도 OOM 방지를 보장하지 않습니다.

테스트에서 계측이 필요 없으면 Spring 설정 `logfriends.agent.enabled=false`를 사용합니다.
Actuator의 `logfriends` endpoint를 노출하면 `/actuator/logfriends`에서 SDK 통계를 볼 수 있습니다. 외부에는 공개하지 마세요.

코드는 `LogFriendsInstaller`(시작 설정), `LogFriendsAutoConfiguration`(등록·heartbeat), `BatchTransporter`(전송) 순서로 읽으면 됩니다.
상위 작업공간의 `docs/sdk/kotlin/backend/reading-guide.md`·`package-architecture.md`는 **로컬 전용 문서**이며 이 GitHub 저장소에 포함되지 않습니다.

[Console](https://github.com/log-freind/log-friends-console) · [Examples](https://github.com/log-freind/log-friends-examples) · [Apache-2.0](LICENSE)
