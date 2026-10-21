# APMLite Agent

Java `-javaagent` 기반 WAS·JVM·SQL 모니터링 에이전트다. 요청 처리 경로에서 원격 DB 저장을 수행하지 않으며, 관측 실패 시 데이터를 버리고 서비스 실행을 우선한다.

## 빌드

```bash
mvn -B test
mvn -B package
```

결과물은 `target/apmlite-agent-1.1.0.jar`이다.

## 기동

```bash
java -javaagent:target/apmlite-agent-1.1.0.jar=service=order-api;http_port=8161;sample_ms=2000;slow_ms=300 -jar myapp.jar
```

관리자 UI: `http://127.0.0.1:8161/monitor`

기본 바인딩은 loopback이며, 외부 공개가 필요한 경우 별도 인증·접근제어 프록시를 둔다.

## Agent 옵션

| 옵션 | 기본값 | 설명 |
|---|---:|---|
| `service` / `service_name` | `demo-service` | 서비스 표시 이름 |
| `http_port` | `8161` | 관리자 UI/API 포트 |
| `sample_ms` / `interval` | `2000` | JVM·WAS 샘플 주기(ms) |
| `slow_ms` | `300` | 느린 SQL 기준(ms) |
| `alert_tps` | `0` | TPS 경고 기준, 0은 비활성화 |
| `alert_active` | `0` | 동시 요청 경고 기준 |
| `alert_error_percent` | `0` | 오류율 경고 기준(%) |
| `collector_url` | 빈 값 | Collector 수신 주소 |
| `collector_queue_size` | `1000` | Agent 전송 큐 상한 |
| `max_slow_queries` | `100` | 최근 느린 SQL 메모리 보관 건수 |
| `use_log` | `true` | Agent 로그 출력 |

## 관리자 UI와 API

- `/api/health`: Agent 상태와 최신 샘플 지연
- `/api/overview`: Heap, CPU, Thread, GC, QPS
- `/api/traffic`: 전체·URI별 히트, TPS, 동시 요청, 응답시간, 오류율
- `/api/traffic/timeline`: WAS TPS·동시 요청·오류율 추이
- `/api/traffic/errors`: 최근 예외·HTTP 5xx 목록(최대 200건)
- `/api/sql/top`: SQL별 건수·평균·최대 실행시간
- `/api/sql/slow`: 최근 느린 SQL과 SQL 오류
- `/api/sql/timeline`: 실행시간 색상 점 데이터
- `/api/alerts`: TPS·동시성·오류율 경고
- `/api/collector/status`: Agent 큐와 전송 통계
- `/api/collector/summary`: Collector 시간 버킷 이력

SQL 점 색상은 3초 미만 초록, 3초 이상 10초 미만 노랑, 10초 이상 빨강, 오류 보라색이다. SQL 바인딩 값은 수집하지 않는다.

## Collector

```bash
APMLITE_COLLECTOR_PORT=9000 APMLITE_COLLECTOR_DB=/var/lib/apmlite/collector.db \
  python3 collector/apmlite_collector.py
```

Agent 설정: `collector_url=http://127.0.0.1:9000/events;collector_queue_size=1000`

Collector는 `JVM`, `SQL`, `TRAFFIC` 배치를 받아 1분 버킷 집계와 최근 1,000건 샘플만 SQLite에 저장한다. 큐가 가득 차면 이벤트를 폐기하고 202로 응답한다. 자세한 내용은 [collector/README.md](collector/README.md)를 참조한다.

## 저장·보안·호환성 원칙

- 요청 스레드에서 DB 연결·저장을 수행하지 않는다.
- Agent와 Collector 큐는 bounded이며, 전송 실패 시 재시도 폭주를 만들지 않는다.
- SQL 파라미터, 비밀번호, 토큰, 사용자 식별자를 저장하지 않는다.
- 사용자 식별자가 없으면 접속자 수를 `Unknown`으로 표시한다.
- Agent 기능 장애가 대상 서비스 예외·지연·스레드 고갈로 전파되지 않아야 한다.
- Java 8 바이트코드로 컴파일하며 Java 8 이상에서 실행한다.

## 검증

```bash
mvn -B test
mvn -B package
python3 -m py_compile collector/apmlite_collector.py
```

실제 JVM smoke에서는 `-javaagent` 기동, HTTP 계측, health·overview·traffic·SQL API를 확인한다. 테스트 환경에서 네트워크 바인딩이 제한된 경우에는 JVM 계측과 API 생성 검증을 분리해 수행한다.
