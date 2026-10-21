# 모니터링 데이터 저장 아키텍처

## 원칙

Tomcat에서 실행되는 Agent는 서비스 요청 경로를 방해하지 않는다. Agent가 운영 DB에 직접 연결해 이력을 저장하면 DB 지연·장애가 요청 처리 JVM으로 전파될 수 있으므로, DB 저장은 별도 Collector가 담당한다.

```text
Tomcat + Agent → localhost Collector → Monitoring DB
```

## Agent 책임

- 요청·트랜잭션·SQL 이벤트를 짧은 구조체로 만든다.
- bounded memory queue에 넣고 즉시 반환한다.
- Collector가 중단되거나 큐가 가득 차면 관측 데이터를 폐기한다.
- SQL 파라미터, 비밀번호, 개인정보를 이벤트에 넣지 않는다.
- Collector 통신 실패를 대상 요청 예외로 전파하지 않는다.

## Collector 책임

- Agent 이벤트를 수신한다.
- 배치와 연결 풀을 사용해 모니터링 DB에 저장한다.
- DB 타임아웃·재시도·보존 기간을 관리한다.
- 여러 Tomcat 인스턴스의 이벤트를 통합한다.
- 수신량, 저장 성공·실패, 폐기량, 큐 길이를 자체 모니터링한다.

## 저장 보호 조건

- Agent queue와 Collector queue는 모두 bounded로 제한한다.
- DB 장애 시 무제한 재시도나 무제한 디스크·메모리 적재를 하지 않는다.
- 저장 작업은 요청 스레드에서 수행하지 않는다.
- DB 연결·쿼리 타임아웃을 설정한다.
- 관측 데이터 손실은 허용하지만 서비스 요청 실패·스레드 고갈·지속적인 지연 증가는 허용하지 않는다.

Agent에는 `collector_url`을 지정할 수 있는 bounded 메모리 버퍼가 추가되었다. `collector_url`이 설정되면 JVM·느린 SQL 이벤트를 비차단 `offer`로 버퍼링하고 1초 배치로 HTTP POST한다. 전송 실패나 큐 포화 시 이벤트를 폐기하며 `/api/collector/status`에서 큐·폐기·전송 통계를 확인한다. 기본값은 비활성화이며, 기존 `use_db=true` 직접 저장 경로는 호환성을 위한 실험용으로만 사용한다.
