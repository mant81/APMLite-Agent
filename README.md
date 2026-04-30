# APMLite Agent

`APMLite Agent` is a lightweight Java APM prototype that moves this project closer to a Jennifer-style monitoring experience.

It runs as a `-javaagent`, instruments JDBC `PreparedStatement` execution, collects JVM metrics, and exposes a built-in live dashboard over HTTP.

## What It Does

- Collects JVM metrics: heap, non-heap, thread count, class count, GC, uptime, CPU
- Tracks SQL execution counts, average latency, max latency, and error counts
- Detects slow queries and keeps a recent in-memory event list
- Serves a simple real-time dashboard on `http://127.0.0.1:8161` (or `/monitor`)
- Optionally persists metric snapshots and slow queries into MariaDB

## Project Layout

```text
src/main/java/com/sschoi/APMLiteAgent.java
pom.xml
README.md
```

<<<<<<< HEAD
```
apmlite-agent/
├── src/main/java/com/sschoi/APMLiteAgent.java
├── pom.xml
├── README.md
└── target/apmlite-agent-1.0.0.jar
```

---

## ⚙️ pom.xml (최종)

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.sschoi</groupId>
    <artifactId>apmlite-agent</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <name>APMLite Agent</name>

    <dependencies>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy</artifactId>
            <version>1.14.12</version>
        </dependency>
        <dependency>
            <groupId>net.bytebuddy</groupId>
            <artifactId>byte-buddy-agent</artifactId>
            <version>1.14.12</version>
        </dependency>
        <dependency>
            <groupId>org.mariadb.jdbc</groupId>
            <artifactId>mariadb-java-client</artifactId>
            <version>2.7.9</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.4.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <manifestEntries>
                                        <Premain-Class>com.sschoi.APMLiteAgent</Premain-Class>
                                        <Can-Redefine-Classes>true</Can-Redefine-Classes>
                                        <Can-Retransform-Classes>true</Can-Retransform-Classes>
                                    </manifestEntries>
                                </transformer>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```
---

## 🧩 빌드 방법
```
mvn clean package
```

- 결과 파일: target/apmlite-agent-1.0.0.jar
- 모든 의존성 포함된 Fat JAR 생성

---
=======
## Build

```bash
mvn clean package
```

Output jar:

```text
target/apmlite-agent-1.1.0.jar
```

## Run
>>>>>>> 1

Example:

<<<<<<< HEAD
Java 애플리케이션 실행 시 -javaagent 옵션 추가:

```
java -javaagent:/path/to/apmlite-agent-1.0.0.jar=use_db=true;db_url=jdbc:mariadb://localhost:3306/monitor;db_user=user;db_pass=password -jar myapp.jar
```

---
=======
```bash
java -javaagent:target/apmlite-agent-1.1.0.jar=service=order-api;http_port=8161;slow_ms=300;use_db=false -jar myapp.jar
```

Then open:

```text
http://127.0.0.1:8161
http://127.0.0.1:8161/monitor
```
>>>>>>> 1

Health check endpoint:

```text
http://127.0.0.1:8161/api/health
```

<<<<<<< HEAD
**예시:**
```bash
-javaagent:apmlite-agent.jar=use_db=true;slow_ms=500;use_log=true
```

---
=======
## Agent Options

| Option | Default | Description |
|---|---:|---|
| `service` or `service_name` | `demo-service` | Service name shown in the dashboard |
| `http_port` | `8161` | Embedded dashboard port |
| `slow_ms` | `300` | Slow query threshold in milliseconds |
| `sample_ms` or `interval` | `2000` | JVM sampling interval in milliseconds |
| `use_db` | `false` | Persist data to MariaDB |
| `db_url` | `jdbc:mariadb://localhost:3306/apm_lite` | MariaDB JDBC URL |
| `db_user` | `root` | Database username |
| `db_pass` | `admin` | Database password |
| `use_log` | `true` | Print agent logs to stdout |
| `max_slow_queries` | `100` | Number of recent slow/error queries kept in memory |
| `timeline_points` | `60` | Number of dashboard history points kept in memory |

Example with DB persistence:

```bash
java -javaagent:target/apmlite-agent-1.1.0.jar=service=payment-api;use_db=true;db_url=jdbc:mariadb://localhost:3306/apm_lite;db_user=root;db_pass=admin -jar myapp.jar
```
>>>>>>> 1

## Database Tables

<<<<<<< HEAD
```sql
CREATE TABLE IF NOT EXISTS apm_query_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '고유 식별자',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '로그 시각',
    query_text TEXT COMMENT '실행된 SQL 쿼리',
    exec_time_ms BIGINT COMMENT '실행 시간 (ms)',
    threshold_ms INT COMMENT '기준 임계치 (ms)',
    status VARCHAR(10) COMMENT 'Slow / Normal',
    INDEX idx_created_at (created_at),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='APMLite SQL 실행 로그';
```

---

## 📊 콘솔 출력 예시

```
[APMLite] Agent started successfully.
[APMLite] Captured SlowQuery: 324ms
SQL: SELECT * FROM users WHERE id = ?
```

---

## ⚠️ 안정성 및 주의사항

- 모든 JDBC 호출은 Proxy 객체로 감싸지만, 원본 PreparedStatement를 그대로 위임하므로 성능 저하 최소화
- DB 장애 시에도 메인 서비스는 영향을 받지 않음 (로그만 경고 출력)
- Thread-safe하게 동작
- ByteBuddy Agent는 클래스 로드 시점에만 개입하므로 런타임 영향 거의 없음

---

## 🧑‍💻 개발 정보

| 항목         | 내용                         |
| ---------- | -------------------------- |
| Language   | Java 8 이상                  |
| Build Tool | Maven                      |
| Database   | MariaDB                    |
| Agent Type | Java Agent (Premain-Class) |
| Library    | ByteBuddy                  |
| Version    | 1.0.0                      |
| License    | MIT                        |

---


## 📄 License
```
MIT License
Copyright (c) 2025
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files...
```
=======
When `use_db=true`, the agent creates these tables automatically if they do not already exist:

- `apm_metric_samples`
- `apm_slow_queries`

## Jennifer-Like Direction

This is still a lightweight prototype, but it now has the core shape of an APM tool:
>>>>>>> 1

- agent-based collection
- near-real-time metric view
- slow query analysis
- top SQL aggregation
- service-level dashboard

## Next Recommended Steps

- Add servlet or Spring controller tracing to measure transactions
- Capture external calls such as Redis, HTTP clients, and Kafka
- Add alert rules for heap, QPS drop, and slow query bursts
- Split collector/dashboard from the agent so multiple JVMs can report centrally
