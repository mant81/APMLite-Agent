# 2026-10-22 대시보드 JavaScript 문법 오류 수정

## 재현과 원인

- 기존 `dist/apmlite-agent-1.1.0.jar`을 Java 25.0.2에 `-javaagent`로 연결했다. `/`와 `/monitor`가 반환한 인라인 스크립트를 Node.js 24.14.1의 JavaScript 파서로 읽으면 `SyntaxError: Invalid or unexpected token`이 발생했다.
- 배포 JAR의 SQL 포맷터는 JavaScript 문자열 리터럴 안에 실제 개행을 출력했다. 스크립트 전체의 파싱이 실패해 대시보드 초기화도 실행되지 않는다.
- 현재 소스의 개행 이스케이프와 배포 JAR이 일치하지 않았다. 소스에는 공백 정규식의 Java 이스케이프도 하나 누락되어 있었다. Java 25에서는 해당 `\s`가 공백 문자로 해석되며 기존 Maven source 8 설정에서는 컴파일 오류가 발생한다.

## 변경

- Java 문자열에서 JavaScript 공백 정규식을 `\\s`로 이스케이프했다.
- SQL 포맷터 문자열을 구분하고, JavaScript 문자열에 실제 개행을 넣지 않도록 주석을 추가했다.
- Java 25.0.2에서 Maven으로 다시 패키징해 `dist/apmlite-agent-1.1.0.jar`을 갱신했다. 기존 Maven source/target 설정은 유지했다.
- 실제 HTTP 응답을 파싱하는 회귀 검증과 SQL 줄바꿈·탭·따옴표·키워드 경계·모달 클릭 검증을 추가했다.

## 검증

PowerShell에서 Java 25 JDK와 Maven 경로를 지정한 뒤 실행한다. Python 3 및 Node.js가 PATH에 있어야 한다.

```powershell
$env:JAVA_HOME = 'D:/sw/java/jdk-25.0.2'
$env:PATH = "$env:JAVA_HOME/bin;D:/sw/maven/maven-3.9.15/bin;" + $env:PATH
mvn -B clean test package
python tests/dashboard_smoke.py target/apmlite-agent-1.1.0.jar
```

- Maven 빌드 성공. Surefire에서 실행할 Java 단위 테스트 소스는 없으며, 회귀 검증은 위 Python/Node 명령으로 별도 실행했다.
- 변경 전 배포 JAR: JavaScript 문법 검사 실패. 변경 후 JAR: 문법·SQL 포맷·모달 클릭 검증 통과.
- 실제 JVM smoke: DB 비활성화, Collector 미설정, 임시 포트, 200ms 샘플링. health `UP`, overview timestamp 증가, timeline 2개 이상 샘플 확인.
- `/`, `/monitor`, `/api/health`, `/api/overview`, `/api/timeline`, `/api/sql/top`, `/api/sql/slow`, `/api/sql/timeline`, `/api/traffic`, `/api/traffic/timeline`, `/api/traffic/errors`, `/api/alerts`, `/api/collector/status`, `/api/collector/summary` 응답 정상. API 응답은 JSON 파싱까지 검증했다.
- 검증한 target JAR과 갱신한 dist JAR의 SHA-256 일치 확인.
- 실제 브라우저 렌더링, 사용자 WAS 및 실제 JDBC 트래픽은 이번 검증에 포함하지 않았다. SQL API는 DB 없이 응답 형태를 확인했다.

## 보안·호환성·운영 검토

- 보안: SQL 파라미터·비밀번호·토큰을 사용하거나 수집하지 않았다. 모달의 SQL 표시에는 기존 `textContent`를 유지한다.
- 호환성: 빌드 및 실행 환경은 Java 25.0.2이다. 실제 반환되는 스크립트를 Node.js로 검증했으며 브라우저별 호환성은 별도 확인이 필요하다.
- 메모리·서비스 영향: 수정 범위는 대시보드 문자열 생성과 배포물이다. 계측·요청 처리·큐·저장 경로에는 변경이 없다. 부하 및 장애 주입 검증은 수행하지 않았다.
- 기준 APM 대비: 서비스 대시보드 `partial` — 이번 JavaScript 실행 차단 결함과 SQL 상세 보기 동작을 검증했다. 다른 필수 WAS 지표 및 제품 전체 동등성은 이번 작업에서 재평가하지 않았다.

적용 시 대상 JVM을 새 JAR로 재기동하고 대시보드를 새로 고침해야 한다.
