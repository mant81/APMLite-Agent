# APMLite Collector

Agent 이벤트를 별도 프로세스에서 받아 저장하는 참조 Collector다. 애플리케이션 JVM과 모니터링 DB를 분리하고, 원본 이벤트를 무제한 저장하지 않는다.

## 실행

```bash
APMLITE_COLLECTOR_PORT=9000 APMLITE_COLLECTOR_DB=/var/lib/apmlite/collector.db \
  python3 collector/apmlite_collector.py
```

Agent는 다음 옵션으로 연결한다.

```text
collector_url=http://127.0.0.1:9000/events;collector_queue_size=1000
```

수신 배치는 최대 500건으로 제한되며, Collector 큐가 가득 차면 해당 이벤트를 폐기하고 202로 응답한다. 저장소에는 1분 버킷별 집계와 최근 1,000건의 제한 샘플만 남긴다.

- `GET /health`: 큐와 수신·폐기·저장 건수
- `GET /summary`: 시간 버킷 집계
- `POST /events`: Agent 이벤트 배치 수신

운영 환경에서는 이 참조 구현을 서비스 매니저로 실행하고 DB 파일 보존·백업 정책을 별도로 설정한다.
