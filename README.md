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

## Build

```bash
mvn clean package
```

Output jar:

```text
target/apmlite-agent-1.1.0.jar
```

## Run

Example:

```bash
java -javaagent:target/apmlite-agent-1.1.0.jar=service=order-api;http_port=8161;slow_ms=300;use_db=false -jar myapp.jar
```

Then open:

```text
http://127.0.0.1:8161
http://127.0.0.1:8161/monitor
```

Health check endpoint:

```text
http://127.0.0.1:8161/api/health
```

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

## Database Tables

When `use_db=true`, the agent creates these tables automatically if they do not already exist:

- `apm_metric_samples`
- `apm_slow_queries`

## Jennifer-Like Direction

This is still a lightweight prototype, but it now has the core shape of an APM tool:

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
