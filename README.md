# 🚀 APMLite Agent v1.0.0 – 경량 SQL & JVM 성능 모니터링 에이전트

**APMLite Agent**는 Java 애플리케이션의 **SQL 실행 시간, JDBC Slow Query, JVM 메모리 사용량** 등을  
애플리케이션 코드 수정 없이 추적할 수 있는 경량 APM(Java Agent)입니다.  
기존 서비스 코드에 영향 없이, 단 한 줄의 JVM 옵션으로 바로 적용 가능합니다.

---

## 🌟 주요 기능

| 기능 | 설명 |
|------|------|
| 🧠 **SQL 실행시간 추적** | PreparedStatement 실행 시간(ms) 측정 |
| 🧾 **Slow Query 감지** | 지정된 임계시간(기본 200ms) 이상 쿼리 자동 로그 |
| 💾 **MySQL 기록** | Slow Query, 메모리 이벤트를 DB에 자동 저장 |
| ⚙️ **설정 유연성** | DB 사용 여부, 로그 간격, 임계값 등 `agentArgs` 로 제어 |
| 🔍 **기존 코드 영향 없음** | `-javaagent` 옵션만으로 동작 |
| 🧩 **ByteBuddy 기반 바이트코드 변환** | JDBC `prepareStatement()` 와 `execute()` 감시 |

---

## 📁 프로젝트 구조

apmlite-agent/
├── src/main/java/com/sschoi/APMLiteAgent.java
├── pom.xml
├── README.md
└── target/apmlite-agent-1.0.0.jar


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

## 🧩 빌드 방법
mvn clean package

결과 파일: target/apmlite-agent-1.0.0.jar
모든 의존성 포함된 Fat JAR 생성



##⚡ 실행 방법

Java 애플리케이션 실행 시 -javaagent 옵션 추가:
java -javaagent:/path/to/apmlite-agent-1.0.0.jar=use_db=true;db_url=jdbc:mariadb://localhost:3306/monitor;db_user=user;db_pass=password -jar myapp.jar



##⚙️ Agent 설정 인자

| 옵션        | 기본값                                     | 설명                    |
| --------- | --------------------------------------- | --------------------- |
| `use_db`  | `true`                                  | DB 로그 기록 여부           |
| `slow_ms` | `200`                                   | Slow Query 기준 시간 (ms) |
| `db_url`  | `jdbc:mariadb://localhost:3306/monitor` | DB URL                |
| `db_user` | `user`                                  | DB 사용자                |
| `db_pass` | `password`                              | DB 비밀번호               |
| `use_log` | `true`                                  | 콘솔 로그 활성화 여부          |

예시:

-javaagent:apmlite-agent.jar=use_db=true;slow_ms=500;use_log=true



##🧾 MySQL DDL

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


## 📊 콘솔 출력 예시

[APMLite] Agent started successfully.
[APMLite] Captured SlowQuery: 324ms
SQL: SELECT * FROM users WHERE id = ?


## ⚠️ 안정성 및 주의사항

- 모든 JDBC 호출은 Proxy 객체로 감싸지만, 원본 PreparedStatement를 그대로 위임하므로 성능 저하 최소화
- DB 장애 시에도 메인 서비스는 영향을 받지 않음 (로그만 경고 출력)
- Thread-safe하게 동작
- ByteBuddy Agent는 클래스 로드 시점에만 개입하므로 런타임 영향 거의 없음



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



## 📄 License
MIT License
Copyright (c) 2025
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files...




