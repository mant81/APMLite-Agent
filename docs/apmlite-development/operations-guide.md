# 운영 가이드

## 배포 순서

1. Collector를 별도 계정·프로세스로 기동한다.
2. Collector `/health`가 `UP`인지 확인한다.
3. 애플리케이션을 `-javaagent`와 함께 기동한다.
4. Agent `/api/health`와 `/monitor`를 loopback에서 확인한다.
5. 트래픽을 발생시켜 `/api/traffic`, SQL 실행과 `/api/sql/timeline`을 확인한다.
6. `alert_tps`, `alert_active`, `alert_error_percent`를 운영 임계값에 맞게 설정한다.

## 권장 설정

```text
service=order-api;sample_ms=2000;slow_ms=300;
collector_url=http://127.0.0.1:9000/events;collector_queue_size=1000;
alert_tps=100;alert_active=200;alert_error_percent=5
```

## 보존 정책

- Agent JVM: WAS 추이 180점, SQL 점 5,000건, 느린 SQL 100건, HTTP 오류 200건
- Collector SQLite: 시간 버킷 집계와 최근 이벤트 1,000건
- 장기 보존이 필요하면 Collector 집계 테이블만 별도 백업·삭제 정책으로 관리한다.

## 장애 대응

- Agent 큐가 가득 차면 이벤트를 버린다. 애플리케이션 요청은 계속 처리한다.
- Collector가 중단되면 `/api/collector/status`의 `sendFailures`와 `dropped`를 확인한다.
- Agent dashboard 포트가 사용 중이면 계측은 계속되고 dashboard만 비활성화된다.
- DB 또는 Collector 장애를 애플리케이션 오류로 판단하지 않는다.

## 점검 항목

- `/api/health`의 `sampleAgeMs`가 샘플 주기보다 과도하게 크지 않은가
- `/api/traffic`의 TPS·동시 요청·오류율이 기대값과 일치하는가
- `/api/traffic/errors`에 예외·5xx가 기록되는가
- SQL 색상 기준이 3초·10초 경계와 일치하는가
- Collector의 큐가 지속적으로 상한에 도달하지 않는가
