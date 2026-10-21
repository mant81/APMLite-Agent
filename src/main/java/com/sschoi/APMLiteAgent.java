package com.sschoi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class APMLiteAgent {

    private static final Config CONFIG = new Config();
    private static final MetricsStore STORE = new MetricsStore();
    private static final TrafficMetrics TRAFFIC = new TrafficMetrics();
    private static final CollectorBuffer COLLECTOR = new CollectorBuffer();
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(namedFactory("apmlite-db"));
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, namedFactory("apmlite-scheduler"));

    public static void recordHttpEnter(Object request) {
        TRAFFIC.enter(request);
    }

    public static void recordHttpExit(Object request, Object response, long durationNanos, Throwable thrown) {
        TRAFFIC.exit();
        TRAFFIC.observe(request, response, durationNanos, thrown);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        CONFIG.apply(agentArgs);
        log("Starting agent for service '" + CONFIG.serviceName + "'");

        COLLECTOR.start();
        initDatabase();
        startJvmSampler();
        startHttpServer();
        installJdbcInstrumentation(inst);
        installHttpInstrumentation(inst);

        log("Agent ready. Dashboard: http://127.0.0.1:" + CONFIG.httpPort);
    }

    private static void initDatabase() {
        if (!CONFIG.useDb) {
            return;
        }

        DB_EXECUTOR.submit(() -> {
            try {
                loadJdbcDriver();
                try (Connection conn = DriverManager.getConnection(CONFIG.dbUrl, CONFIG.dbUser, CONFIG.dbPassword)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "CREATE TABLE IF NOT EXISTS apm_metric_samples (" +
                                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                                    "service_name VARCHAR(100) NOT NULL," +
                                    "heap_used_mb DOUBLE," +
                                    "heap_max_mb DOUBLE," +
                                    "heap_usage_pct DOUBLE," +
                                    "non_heap_used_mb DOUBLE," +
                                    "thread_count INT," +
                                    "daemon_thread_count INT," +
                                    "loaded_class_count INT," +
                                    "process_cpu_load DOUBLE," +
                                    "system_cpu_load DOUBLE," +
                                    "queries_per_sec BIGINT" +
                                    ")")) {
                        ps.execute();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "CREATE TABLE IF NOT EXISTS apm_slow_queries (" +
                                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                                    "service_name VARCHAR(100) NOT NULL," +
                                    "duration_ms BIGINT NOT NULL," +
                                    "status VARCHAR(20) NOT NULL," +
                                    "sql_text LONGTEXT," +
                                    "error_message VARCHAR(500)" +
                                    ")")) {
                        ps.execute();
                    }
                }
            } catch (Exception e) {
                log("Database init failed: " + e.getMessage());
            }
        });
    }

    private static void startJvmSampler() {
        final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        final ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
        final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                MemoryUsage heap = memoryBean.getHeapMemoryUsage();
                MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

                double heapUsedMb = toMb(heap.getUsed());
                double heapMaxMb = heap.getMax() <= 0 ? 0D : toMb(heap.getMax());
                double heapUsagePct = heapMaxMb == 0D ? 0D : (heapUsedMb / heapMaxMb) * 100D;
                double nonHeapUsedMb = toMb(nonHeap.getUsed());

                long totalGcCount = 0L;
                long totalGcTimeMs = 0L;
                for (GarbageCollectorMXBean gcBean : gcBeans) {
                    long collectionCount = gcBean.getCollectionCount();
                    long collectionTime = gcBean.getCollectionTime();
                    if (collectionCount > 0) {
                        totalGcCount += collectionCount;
                    }
                    if (collectionTime > 0) {
                        totalGcTimeMs += collectionTime;
                    }
                }

                JvmSnapshot snapshot = new JvmSnapshot();
                snapshot.timestamp = System.currentTimeMillis();
                snapshot.serviceName = CONFIG.serviceName;
                snapshot.heapUsedMb = heapUsedMb;
                snapshot.heapMaxMb = heapMaxMb;
                snapshot.heapUsagePct = heapUsagePct;
                snapshot.nonHeapUsedMb = nonHeapUsedMb;
                snapshot.threadCount = threadBean.getThreadCount();
                snapshot.daemonThreadCount = threadBean.getDaemonThreadCount();
                snapshot.loadedClassCount = classBean.getLoadedClassCount();
                snapshot.totalLoadedClassCount = classBean.getTotalLoadedClassCount();
                snapshot.unloadedClassCount = classBean.getUnloadedClassCount();
                snapshot.gcCount = totalGcCount;
                snapshot.gcTimeMs = totalGcTimeMs;
                snapshot.uptimeMs = runtimeBean.getUptime();
                snapshot.processCpuLoad = CpuSampler.readProcessCpuLoad();
                snapshot.systemCpuLoad = CpuSampler.readSystemCpuLoad();
                snapshot.queriesPerSec = STORE.rotateQueriesPerSecond();
                TRAFFIC.rotateHitsPerSecond();

                STORE.setSnapshot(snapshot);
                STORE.addTimelinePoint(snapshot);
                COLLECTOR.offer(snapshot);
                COLLECTOR.offer(TRAFFIC.snapshot());
                TRAFFIC.addTimelinePoint();
                persistSnapshot(snapshot);
            } catch (Exception e) {
                log("JVM sampler error: " + e.getMessage());
            }
        }, 0L, CONFIG.sampleIntervalMs, TimeUnit.MILLISECONDS);
    }

    private static void persistSnapshot(final JvmSnapshot snapshot) {
        if (!CONFIG.useDb) {
            return;
        }

        DB_EXECUTOR.submit(() -> {
            try {
                loadJdbcDriver();
                try (Connection conn = DriverManager.getConnection(CONFIG.dbUrl, CONFIG.dbUser, CONFIG.dbPassword);
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO apm_metric_samples " +
                                     "(service_name, heap_used_mb, heap_max_mb, heap_usage_pct, non_heap_used_mb, " +
                                     "thread_count, daemon_thread_count, loaded_class_count, process_cpu_load, system_cpu_load, queries_per_sec) " +
                                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, snapshot.serviceName);
                    ps.setDouble(2, snapshot.heapUsedMb);
                    ps.setDouble(3, snapshot.heapMaxMb);
                    ps.setDouble(4, snapshot.heapUsagePct);
                    ps.setDouble(5, snapshot.nonHeapUsedMb);
                    ps.setInt(6, snapshot.threadCount);
                    ps.setInt(7, snapshot.daemonThreadCount);
                    ps.setInt(8, snapshot.loadedClassCount);
                    ps.setDouble(9, snapshot.processCpuLoad);
                    ps.setDouble(10, snapshot.systemCpuLoad);
                    ps.setLong(11, snapshot.queriesPerSec);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                log("Metric insert failed: " + e.getMessage());
            }
        });
    }

    private static void persistSlowQuery(final SlowQueryEvent event) {
        if (!CONFIG.useDb) {
            return;
        }

        DB_EXECUTOR.submit(() -> {
            try {
                loadJdbcDriver();
                try (Connection conn = DriverManager.getConnection(CONFIG.dbUrl, CONFIG.dbUser, CONFIG.dbPassword);
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO apm_slow_queries (service_name, duration_ms, status, sql_text, error_message) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, CONFIG.serviceName);
                    ps.setLong(2, event.durationMs);
                    ps.setString(3, event.status);
                    ps.setString(4, event.sql);
                    ps.setString(5, event.errorMessage);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                log("Slow query insert failed: " + e.getMessage());
            }
        });
    }

    private static void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(CONFIG.httpPort), 0);
            server.createContext("/", exchange -> writeResponse(exchange, "text/html; charset=utf-8", buildDashboardHtml()));
            server.createContext("/monitor", exchange -> writeResponse(exchange, "text/html; charset=utf-8", buildDashboardHtml()));
            server.createContext("/api/health", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.healthJson()));
            server.createContext("/api/overview", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.overviewJson()));
            server.createContext("/api/timeline", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.timelineJson()));
            server.createContext("/api/sql/top", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.topSqlJson()));
            server.createContext("/api/sql/slow", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.slowQueriesJson()));
            server.createContext("/api/sql/timeline", exchange -> writeResponse(exchange, "application/json; charset=utf-8", STORE.sqlTimelineJson()));
            server.createContext("/api/traffic", exchange -> writeResponse(exchange, "application/json; charset=utf-8", TRAFFIC.json()));
            server.createContext("/api/traffic/errors", exchange -> writeResponse(exchange, "application/json; charset=utf-8", TRAFFIC.errorsJson()));
            server.createContext("/api/traffic/timeline", exchange -> writeResponse(exchange, "application/json; charset=utf-8", TRAFFIC.timelineJson()));
            server.createContext("/api/alerts", exchange -> writeResponse(exchange, "application/json; charset=utf-8", TRAFFIC.alertsJson()));
            server.createContext("/api/collector/status", exchange -> writeResponse(exchange, "application/json; charset=utf-8", COLLECTOR.statusJson()));
            server.createContext("/api/collector/summary", exchange -> writeResponse(exchange, "application/json; charset=utf-8", COLLECTOR.summaryJson()));
            server.setExecutor(Executors.newCachedThreadPool(namedFactory("apmlite-http")));
            server.start();
        } catch (IOException e) {
            log("Dashboard disabled on port " + CONFIG.httpPort + ": " + e.getMessage());
        }
    }

    private static void writeResponse(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void installJdbcInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.isSubTypeOf(Connection.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(ElementMatchers.named("prepareStatement").and(ElementMatchers.takesArguments(String.class)))
                                .intercept(Advice.to(PrepareStatementAdvice.class)))
                .installOn(inst);
    }

    private static void installHttpInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("javax.servlet.http.HttpServlet")
                        .or(ElementMatchers.named("jakarta.servlet.http.HttpServlet")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(ElementMatchers.named("service"))
                                .intercept(Advice.to(HttpServiceAdvice.class)))
                .installOn(inst);
    }

    public static class HttpServiceAdvice {
        @Advice.OnMethodEnter
        public static long onEnter(@Advice.Argument(0) Object request) {
            APMLiteAgent.recordHttpEnter(request);
            return System.nanoTime();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter long startedAt,
                                  @Advice.Argument(0) Object request,
                                  @Advice.Argument(1) Object response,
                                  @Advice.Thrown Throwable thrown) {
            APMLiteAgent.recordHttpExit(request, response, System.nanoTime() - startedAt, thrown);
        }
    }

    public static class PrepareStatementAdvice {

        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(readOnly = false) PreparedStatement stmt,
                                  @Advice.Argument(0) String sql) {
            if (stmt == null || Proxy.isProxyClass(stmt.getClass())) {
                return;
            }
            stmt = createProxy(stmt, sql);
        }

        public static PreparedStatement createProxy(final PreparedStatement stmt, final String sql) {
            final ConcurrentHashMap<Integer, Object> params = new ConcurrentHashMap<Integer, Object>();
            final InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String methodName = method.getName();
                    if (isParameterBinding(methodName, args)) {
                        params.put((Integer) args[0], args[1]);
                    } else if ("clearParameters".equals(methodName)) {
                        params.clear();
                    }

                    long start = System.nanoTime();
                    Throwable error = null;
                    try {
                        return method.invoke(stmt, args);
                    } catch (Throwable t) {
                        error = t.getCause() != null ? t.getCause() : t;
                        throw error;
                    } finally {
                        if (methodName.startsWith("execute")) {
                            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                            String normalizedSql = STORE.normalizeSql(sql);
                            STORE.recordSqlExecution(normalizedSql, durationMs, error);

                            if (durationMs >= CONFIG.slowQueryThresholdMs || error != null) {
                                SlowQueryEvent event = new SlowQueryEvent();
                                event.timestamp = System.currentTimeMillis();
                                event.durationMs = durationMs;
                                event.sql = normalizedSql;
                                event.status = error == null ? "SLOW" : "ERROR";
                                event.errorMessage = error == null ? null : truncate(error.toString(), 500);
                                STORE.addSlowQuery(event);
                                COLLECTOR.offer(event);
                                persistSlowQuery(event);

                                if (CONFIG.useLog) {
                                    log("SQL " + event.status + " " + durationMs + "ms :: " + truncate(normalizedSql, 220));
                                }
                            }
                        }
                    }
                }
            };

            return (PreparedStatement) Proxy.newProxyInstance(
                    stmt.getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class},
                    handler);
        }

        private static boolean isParameterBinding(String methodName, Object[] args) {
            return methodName.startsWith("set")
                    && args != null
                    && args.length >= 2
                    && args[0] instanceof Integer;
        }

        private static String fillParams(String sql, ConcurrentHashMap<Integer, Object> params) {
            if (sql == null) {
                return "";
            }
            String filled = sql;
            List<Integer> indexes = new ArrayList<Integer>(params.keySet());
            Collections.sort(indexes);
            for (Integer index : indexes) {
                Object value = params.get(index);
                String rendered = renderSqlLiteral(value);
                filled = filled.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(rendered));
            }
            return filled;
        }

        private static String renderSqlLiteral(Object value) {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            if (value instanceof Timestamp) {
                return "'" + value.toString() + "'";
            }
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
    }

    private static String buildDashboardHtml() {
        return "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>APMLite Agent Dashboard</title>"
                + "<style>"
                + ":root{--bg:#0b1220;--panel:#101a2f;--panel2:#16233d;--text:#edf4ff;--muted:#9ab0d3;--accent:#67e8f9;--warn:#fcd34d;--bad:#fda4af;}"
                + "*{box-sizing:border-box}body{margin:0;font-family:'Segoe UI',sans-serif;background:radial-gradient(circle at top,#183055 0,#0b1220 55%);color:var(--text)}"
                + ".wrap{max-width:1280px;margin:0 auto;padding:24px}.hero{display:flex;justify-content:space-between;align-items:end;gap:16px;margin-bottom:20px}"
                + "h1{margin:0;font-size:34px}p{margin:6px 0 0;color:var(--muted)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}"
                + ".card{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid rgba(154,176,211,.16);border-radius:18px;padding:18px;box-shadow:0 18px 40px rgba(0,0,0,.24)}"
                + ".label{font-size:12px;letter-spacing:.08em;color:var(--muted);text-transform:uppercase}.value{font-size:28px;font-weight:700;margin-top:10px}"
                + ".split{display:grid;grid-template-columns:2fr 1.2fr;gap:14px;margin-top:14px}.table{width:100%;border-collapse:collapse;margin-top:10px}"
                + ".table th,.table td{text-align:left;padding:10px 8px;border-bottom:1px solid rgba(154,176,211,.12);font-size:14px;vertical-align:top}"
                + ".pill{display:inline-block;padding:4px 8px;border-radius:999px;font-size:12px;font-weight:700}.pill.slow{background:rgba(245,158,11,.14);color:var(--warn)}.pill.error{background:rgba(251,113,133,.14);color:var(--bad)}"
                + ".pill.ok{background:rgba(52,211,153,.14);color:#6ee7b7}.metric-list{display:flex;gap:18px;flex-wrap:wrap;margin-top:12px}.metric-list strong{display:block;font-size:22px}.sql-points{display:flex;gap:5px;align-items:flex-end;min-height:90px;overflow:auto;margin-top:12px}.sql-point{width:10px;height:10px;border-radius:50%;flex:0 0 auto}.sql-point.green{background:#4ade80}.sql-point.yellow{background:#facc15}.sql-point.red{background:#f87171}.sql-point.error{background:#c084fc}"
                + ".muted{color:var(--muted)}#timeline{display:flex;align-items:flex-end;gap:6px;height:180px;margin-top:14px}.bar{flex:1;min-width:6px;border-radius:8px 8px 2px 2px;background:linear-gradient(180deg,var(--accent),#2563eb)}"
                + "@media (max-width:920px){.split{grid-template-columns:1fr}.hero{align-items:start;flex-direction:column}}"
                + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"hero\"><div><h1>APMLite Agent</h1><p>Lightweight JVM APM dashboard</p></div><div class=\"muted\">Service <span id=\"service\">-</span></div></div>"
                + "<div class=\"grid\">"
                + "<div class=\"card\"><div class=\"label\">Heap Usage</div><div class=\"value\" id=\"heapPct\">-</div><div class=\"muted\" id=\"heapDetail\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">QPS</div><div class=\"value\" id=\"qps\">-</div><div class=\"muted\">Queries per second from the latest sample</div></div>"
                + "<div class=\"card\"><div class=\"label\">Threads</div><div class=\"value\" id=\"threads\">-</div><div class=\"muted\" id=\"daemonThreads\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">CPU</div><div class=\"value\" id=\"cpu\">-</div><div class=\"muted\" id=\"sysCpu\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">Slow Queries</div><div class=\"value\" id=\"slowCount\">-</div><div class=\"muted\">Total captured slow or error queries</div></div>"
                + "</div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">WAS Traffic</div><div class=\"metric-list\"><div><span class=\"muted\">TPS</span><strong id=\"trafficTps\">-</strong></div><div><span class=\"muted\">Active</span><strong id=\"trafficActive\">-</strong></div><div><span class=\"muted\">Errors</span><strong id=\"trafficErrorRate\">-</strong></div><div><span class=\"muted\">Users</span><strong id=\"trafficUsers\">Unknown</strong></div><div><span class=\"muted\">Alerts</span><strong id=\"alertState\">-</strong></div></div><table class=\"table\"><thead><tr><th>URI</th><th>Hits</th><th>Avg</th><th>Max</th><th>Errors</th></tr></thead><tbody id=\"trafficRows\"></tbody></table></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">WAS Trend</div><div class=\"muted\">Recent TPS and active request samples</div><div id=\"trafficTrend\" class=\"sql-points\"></div></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">Active Alerts</div><table class=\"table\"><thead><tr><th>Type</th><th>Scope</th><th>Value</th><th>Threshold</th></tr></thead><tbody id=\"alertRows\"></tbody></table></div>"
                + "<div class=\"split\">"
                + "<div class=\"card\"><div class=\"label\">Heap Timeline</div><div id=\"timeline\"></div></div>"
                + "<div class=\"card\"><div class=\"label\">Top SQL</div><table class=\"table\"><thead><tr><th>SQL</th><th>Count</th><th>Avg</th><th>Max</th></tr></thead><tbody id=\"sqlTop\"></tbody></table></div>"
                + "</div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">Recent Slow Queries</div><table class=\"table\"><thead><tr><th>Time</th><th>Status</th><th>Duration</th><th>SQL</th></tr></thead><tbody id=\"slowRows\"></tbody></table></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">Recent Exceptions and HTTP Errors</div><table class=\"table\"><thead><tr><th>Time</th><th>URI</th><th>Status</th><th>Message</th></tr></thead><tbody id=\"errorRows\"></tbody></table></div>"
                + "<div class=\"split\"><div class=\"card\"><div class=\"label\">SQL Execution Timeline</div><div class=\"muted\">Green &lt; 3s · Yellow 3–10s · Red ≥ 10s · Purple error</div><div id=\"sqlPoints\" class=\"sql-points\"></div></div><div class=\"card\"><div class=\"label\">Collector</div><div class=\"metric-list\"><div><span class=\"muted\">Queue</span><strong id=\"collectorQueue\">-</strong></div><div><span class=\"muted\">Dropped</span><strong id=\"collectorDropped\">-</strong></div><div><span class=\"muted\">Sent</span><strong id=\"collectorSent\">-</strong></div></div></div></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">Collector History</div><table class=\"table\"><thead><tr><th>Bucket</th><th>Service</th><th>Type</th><th>Count</th><th>Errors</th><th>Max</th></tr></thead><tbody id=\"collectorRows\"></tbody></table></div>"
                + "</div><script>"
                + "async function load(){const [overview,timeline,top,slow,traffic,alerts,sqlTimeline,collector,errors,trafficTimeline,collectorSummary]=await Promise.all([fetch('/api/overview').then(r=>r.json()),fetch('/api/timeline').then(r=>r.json()),fetch('/api/sql/top').then(r=>r.json()),fetch('/api/sql/slow').then(r=>r.json()),fetch('/api/traffic').then(r=>r.json()),fetch('/api/alerts').then(r=>r.json()),fetch('/api/sql/timeline').then(r=>r.json()),fetch('/api/collector/status').then(r=>r.json()),fetch('/api/traffic/errors').then(r=>r.json()),fetch('/api/traffic/timeline').then(r=>r.json()),fetch('/api/collector/summary').then(r=>r.json())]);"
                + "document.getElementById('service').textContent=overview.serviceName;"
                + "document.getElementById('heapPct').textContent=overview.heapUsagePct.toFixed(1)+'%';"
                + "document.getElementById('heapDetail').textContent=overview.heapUsedMb.toFixed(1)+' MB / '+overview.heapMaxMb.toFixed(1)+' MB';"
                + "document.getElementById('qps').textContent=overview.queriesPerSec;"
                + "document.getElementById('threads').textContent=overview.threadCount;"
                + "document.getElementById('daemonThreads').textContent='Daemon '+overview.daemonThreadCount;"
                + "document.getElementById('cpu').textContent=(overview.processCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('sysCpu').textContent='System '+(overview.systemCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('slowCount').textContent=overview.slowQueryCount;"
                + "document.getElementById('trafficTps').textContent=traffic.tps;document.getElementById('trafficActive').textContent=traffic.activeRequests;document.getElementById('trafficErrorRate').textContent=traffic.errorRatePercent.toFixed(2)+'%';document.getElementById('trafficUsers').textContent=traffic.uniqueUsersStatus==='unknown'?'Unknown':traffic.uniqueUsers;document.getElementById('alertState').textContent=alerts.status;document.getElementById('alertState').className=alerts.status==='ALERT'?'pill error':'pill ok';"
                + "document.getElementById('trafficRows').innerHTML=traffic.uris.map(i=>'<tr><td>'+escapeHtml(i.uri)+'</td><td>'+i.hits+'</td><td>'+i.avgMs.toFixed(1)+' ms</td><td>'+i.maxMs+' ms</td><td>'+i.errors+' ('+i.errorRatePercent.toFixed(1)+'%)</td></tr>').join('')||'<tr><td colspan=\"5\" class=\"muted\">No HTTP traffic captured yet.</td></tr>';document.getElementById('alertRows').innerHTML=alerts.alerts.map(a=>'<tr><td><span class=\"pill error\">'+a.type+'</span></td><td>'+escapeHtml(a.scope)+'</td><td>'+a.value+'</td><td>'+a.threshold+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No active alerts.</td></tr>';"
                + "const max=Math.max(...timeline.points.map(p=>p.heapUsagePct),1);document.getElementById('timeline').innerHTML=timeline.points.map(p=>'<div class=\"bar\" title=\"'+p.heapUsagePct.toFixed(1)+'%\" style=\"height:'+Math.max(8,(p.heapUsagePct/max)*100)+'%\"></div>').join('');"
                + "document.getElementById('sqlTop').innerHTML=top.items.map(i=>'<tr><td>'+escapeHtml(i.sql)+'</td><td>'+i.count+'</td><td>'+i.avgMs.toFixed(1)+' ms</td><td>'+i.maxMs+' ms</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No SQL data has been captured yet.</td></tr>';"
                + "document.getElementById('slowRows').innerHTML=slow.items.map(i=>'<tr><td>'+new Date(i.timestamp).toLocaleTimeString()+'</td><td><span class=\"pill '+i.status.toLowerCase()+'\">'+i.status+'</span></td><td>'+i.durationMs+' ms</td><td>'+escapeHtml(i.sql)+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No slow queries have been captured recently.</td></tr>';"
                + "document.getElementById('errorRows').innerHTML=errors.items.map(i=>'<tr><td>'+new Date(i.timestamp).toLocaleTimeString()+'</td><td>'+escapeHtml(i.uri)+'</td><td><span class=\"pill error\">'+i.status+'</span></td><td>'+escapeHtml(i.message)+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No exceptions or HTTP 5xx errors captured recently.</td></tr>';"
                + "document.getElementById('sqlPoints').innerHTML=sqlTimeline.points.slice(-120).map(p=>'<span class=\"sql-point '+p.color+'\" title=\"'+p.durationMs+' ms · '+escapeHtml(p.sql)+'\"></span>').join('')||'<span class=\"muted\">No SQL timeline data yet.</span>';document.getElementById('collectorQueue').textContent=collector.queueSize+'/'+collector.capacity;document.getElementById('collectorDropped').textContent=collector.dropped;document.getElementById('collectorSent').textContent=collector.sent;"
                + "const maxTps=Math.max(...trafficTimeline.points.map(p=>p.tps),1);document.getElementById('trafficTrend').innerHTML=trafficTimeline.points.slice(-120).map(p=>'<span class=\"sql-point green\" style=\"height:'+Math.max(8,(p.tps/maxTps)*80)+'px\" title=\"TPS '+p.tps+' · Active '+p.activeRequests+' · Error '+p.errorRatePercent.toFixed(2)+'%\"></span>').join('')||'<span class=\"muted\">No WAS trend data yet.</span>';"
                + "document.getElementById('collectorRows').innerHTML=(collectorSummary.items||[]).slice(0,100).map(i=>'<tr><td>'+new Date(i.bucket).toLocaleString()+'</td><td>'+escapeHtml(i.service)+'</td><td>'+escapeHtml(i.type)+'</td><td>'+i.count+'</td><td>'+i.errors+'</td><td>'+i.maxDurationMs+' ms</td></tr>').join('')||'<tr><td colspan=\"6\" class=\"muted\">Collector history unavailable or empty.</td></tr>';"
                + "}function escapeHtml(v){return String(v||'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');}load();setInterval(load,2000);"
                + "</script></body></html>";
    }

    private static void loadJdbcDriver() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {
            // Keep running even when DB logging is disabled or unavailable.
        }
    }

    private static double toMb(long bytes) {
        return bytes / (1024D * 1024D);
    }

    private static void log(String message) {
        if (CONFIG.useLog) {
            System.out.println("[APMLiteAgent] " + message);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class CollectorBuffer {
        private BlockingQueue<String> queue;
        private final AtomicLong offered = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong sent = new AtomicLong();
        private final AtomicLong sendFailures = new AtomicLong();

        private void start() {
            queue = new ArrayBlockingQueue<String>(CONFIG.collectorQueueSize);
            if (CONFIG.collectorUrl.trim().isEmpty()) return;
            SCHEDULER.scheduleAtFixedRate(this::flush, 1L, 1L, TimeUnit.SECONDS);
        }

        private void offer(JvmSnapshot snapshot) {
            offer("{\"type\":\"JVM\",\"service\":\"" + escapeJson(snapshot.serviceName) + "\",\"timestamp\":" + snapshot.timestamp + ",\"heapUsedMb\":" + snapshot.heapUsedMb + ",\"heapUsagePct\":" + snapshot.heapUsagePct + ",\"threadCount\":" + snapshot.threadCount + ",\"queriesPerSec\":" + snapshot.queriesPerSec + "}");
        }

        private void offer(SlowQueryEvent event) {
            offer("{\"type\":\"SQL\",\"service\":\"" + escapeJson(CONFIG.serviceName) + "\",\"timestamp\":" + event.timestamp + ",\"durationMs\":" + event.durationMs + ",\"status\":\"" + escapeJson(event.status) + "\",\"sql\":\"" + escapeJson(event.sql) + "\"}");
        }

        private void offer(TrafficSnapshot snapshot) {
            offer("{\"type\":\"TRAFFIC\",\"service\":\"" + escapeJson(CONFIG.serviceName) + "\",\"timestamp\":" + snapshot.timestamp + ",\"tps\":" + snapshot.tps + ",\"activeRequests\":" + snapshot.activeRequests + ",\"errorRatePercent\":" + snapshot.errorRatePercent + "}");
        }

        private void offer(String event) {
            if (CONFIG.collectorUrl.trim().isEmpty()) return;
            offered.incrementAndGet();
            if (queue == null || !queue.offer(event)) dropped.incrementAndGet();
        }

        private void flush() {
            if (queue == null || queue.isEmpty()) return;
            List<String> batch = new ArrayList<String>(100);
            queue.drainTo(batch, 100);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(CONFIG.collectorUrl).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(200);
                connection.setReadTimeout(500);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                String body = "[" + String.join(",", batch) + "]";
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) sent.addAndGet(batch.size());
                else { sendFailures.incrementAndGet(); batch.forEach(this::offer); }
            } catch (Throwable ignored) {
                sendFailures.incrementAndGet();
                batch.forEach(this::offer);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private String statusJson() {
            return "{\"enabled\":" + (!CONFIG.collectorUrl.trim().isEmpty()) + ",\"queueSize\":" + (queue == null ? 0 : queue.size()) + ",\"capacity\":" + CONFIG.collectorQueueSize + ",\"offered\":" + offered.get() + ",\"dropped\":" + dropped.get() + ",\"sent\":" + sent.get() + ",\"sendFailures\":" + sendFailures.get() + "}";
        }

        private String summaryJson() {
            if (CONFIG.collectorUrl.trim().isEmpty()) return "{\"enabled\":false,\"items\":[]}";
            HttpURLConnection connection = null;
            try {
                String endpoint = CONFIG.collectorUrl.endsWith("/events")
                        ? CONFIG.collectorUrl.substring(0, CONFIG.collectorUrl.length() - 7) + "/summary"
                        : CONFIG.collectorUrl + "/summary";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(200);
                connection.setReadTimeout(500);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    byte[] bytes = readAll(connection.getInputStream());
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
                // Dashboard history is optional and must never affect the service.
            } finally {
                if (connection != null) connection.disconnect();
            }
            return "{\"enabled\":true,\"items\":[],\"status\":\"unavailable\"}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }

    private static ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong(1L);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static final class Config {
        private boolean useDb = false;
        private boolean useLog = true;
        private String dbUrl = "jdbc:mariadb://localhost:3306/apm_lite";
        private String dbUser = "root";
        private String dbPassword = "admin";
        private String serviceName = "demo-service";
        private long sampleIntervalMs = 2000L;
        private long slowQueryThresholdMs = 300L;
        private int httpPort = 8161;
        private int maxSlowQueries = 100;
        private int maxTimelinePoints = 60;
        private long alertHitsPerSecond = 0L;
        private long alertActiveRequests = 0L;
        private double alertErrorPercent = 0D;
        private String collectorUrl = "";
        private int collectorQueueSize = 1000;

        private void apply(String agentArgs) {
            if (agentArgs == null || agentArgs.trim().isEmpty()) {
                return;
            }
            String[] parts = agentArgs.split(";");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String key = kv[0].trim().toLowerCase(Locale.ROOT);
                String value = kv[1].trim();
                if ("use_db".equals(key)) {
                    useDb = Boolean.parseBoolean(value);
                } else if ("use_log".equals(key)) {
                    useLog = Boolean.parseBoolean(value);
                } else if ("db_url".equals(key)) {
                    dbUrl = value;
                } else if ("db_user".equals(key)) {
                    dbUser = value;
                } else if ("db_pass".equals(key)) {
                    dbPassword = value;
                } else if ("service".equals(key) || "service_name".equals(key)) {
                    serviceName = value;
                } else if ("interval".equals(key) || "sample_ms".equals(key)) {
                    sampleIntervalMs = Long.parseLong(value);
                } else if ("slow_ms".equals(key) || "slow_query".equals(key)) {
                    slowQueryThresholdMs = Long.parseLong(value);
                } else if ("http_port".equals(key)) {
                    httpPort = Integer.parseInt(value);
                } else if ("max_slow_queries".equals(key)) {
                    maxSlowQueries = Integer.parseInt(value);
                } else if ("timeline_points".equals(key)) {
                    maxTimelinePoints = Integer.parseInt(value);
                } else if ("alert_tps".equals(key) || "alert_hits_per_second".equals(key)) {
                    alertHitsPerSecond = Math.max(0L, Long.parseLong(value));
                } else if ("alert_active".equals(key) || "alert_active_requests".equals(key)) {
                    alertActiveRequests = Math.max(0L, Long.parseLong(value));
                } else if ("alert_error_percent".equals(key)) {
                    alertErrorPercent = Math.max(0D, Math.min(100D, Double.parseDouble(value)));
                } else if ("collector_url".equals(key)) {
                    collectorUrl = value;
                } else if ("collector_queue_size".equals(key)) {
                    collectorQueueSize = Math.max(10, Math.min(100000, Integer.parseInt(value)));
                }
            }
        }
    }

    private static final class CpuSampler {
        private static double readProcessCpuLoad() {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double value = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                return value < 0 ? 0D : value;
            }
            return 0D;
        }

        private static double readSystemCpuLoad() {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double value = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                return value < 0 ? 0D : value;
            }
            return 0D;
        }
    }

    private static final class MetricsStore {
        private final AtomicLong totalQueries = new AtomicLong();
        private final AtomicLong lastQpsBase = new AtomicLong();
        private final AtomicLong slowQueryCount = new AtomicLong();
        private final ConcurrentHashMap<String, SqlStat> sqlStats = new ConcurrentHashMap<String, SqlStat>();
        private final Deque<SlowQueryEvent> slowQueries = new ConcurrentLinkedDeque<SlowQueryEvent>();
        private final Deque<SqlPoint> sqlTimeline = new ConcurrentLinkedDeque<SqlPoint>();
        private final Deque<JvmSnapshot> timeline = new ArrayDeque<JvmSnapshot>();
        private volatile JvmSnapshot latestSnapshot = new JvmSnapshot();

        private void setSnapshot(JvmSnapshot snapshot) {
            latestSnapshot = snapshot;
        }

        private synchronized void addTimelinePoint(JvmSnapshot snapshot) {
            timeline.addLast(snapshot.copy());
            while (timeline.size() > CONFIG.maxTimelinePoints) {
                timeline.removeFirst();
            }
        }

        private long rotateQueriesPerSecond() {
            long current = totalQueries.get();
            return current - lastQpsBase.getAndSet(current);
        }

        private void recordSqlExecution(String sql, long durationMs, Throwable error) {
            totalQueries.incrementAndGet();
            String key = normalizeSql(sql);
            SqlStat stat = sqlStats.computeIfAbsent(key, k -> new SqlStat(k));
            stat.record(durationMs, error != null);
            sqlTimeline.addLast(new SqlPoint(System.currentTimeMillis(), key, durationMs, error != null));
            while (sqlTimeline.size() > 5000) {
                sqlTimeline.pollFirst();
            }
        }

        private void addSlowQuery(SlowQueryEvent event) {
            slowQueryCount.incrementAndGet();
            slowQueries.addFirst(event);
            while (slowQueries.size() > CONFIG.maxSlowQueries) {
                slowQueries.pollLast();
            }
        }

        private String overviewJson() {
            JvmSnapshot snapshot = latestSnapshot.copy();
            return "{"
                    + "\"serviceName\":\"" + escapeJson(snapshot.serviceName) + "\","
                    + "\"timestamp\":" + snapshot.timestamp + ","
                    + "\"heapUsedMb\":" + formatDouble(snapshot.heapUsedMb) + ","
                    + "\"heapMaxMb\":" + formatDouble(snapshot.heapMaxMb) + ","
                    + "\"heapUsagePct\":" + formatDouble(snapshot.heapUsagePct) + ","
                    + "\"nonHeapUsedMb\":" + formatDouble(snapshot.nonHeapUsedMb) + ","
                    + "\"threadCount\":" + snapshot.threadCount + ","
                    + "\"daemonThreadCount\":" + snapshot.daemonThreadCount + ","
                    + "\"loadedClassCount\":" + snapshot.loadedClassCount + ","
                    + "\"gcCount\":" + snapshot.gcCount + ","
                    + "\"gcTimeMs\":" + snapshot.gcTimeMs + ","
                    + "\"uptimeMs\":" + snapshot.uptimeMs + ","
                    + "\"processCpuLoad\":" + formatDouble(snapshot.processCpuLoad) + ","
                    + "\"systemCpuLoad\":" + formatDouble(snapshot.systemCpuLoad) + ","
                    + "\"queriesPerSec\":" + snapshot.queriesPerSec + ","
                    + "\"totalQueries\":" + totalQueries.get() + ","
                    + "\"slowQueryCount\":" + slowQueryCount.get()
                    + "}";
        }

        private String healthJson() {
            JvmSnapshot snapshot = latestSnapshot.copy();
            long ageMs = snapshot.timestamp <= 0 ? -1L : (System.currentTimeMillis() - snapshot.timestamp);
            return "{"
                    + "\"status\":\"UP\","
                    + "\"serviceName\":\"" + escapeJson(CONFIG.serviceName) + "\","
                    + "\"sampleAgeMs\":" + ageMs + ","
                    + "\"timestamp\":" + System.currentTimeMillis()
                    + "}";
        }

        private synchronized String timelineJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"points\":[");
            boolean first = true;
            for (JvmSnapshot point : timeline) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{")
                        .append("\"timestamp\":").append(point.timestamp).append(',')
                        .append("\"heapUsagePct\":").append(formatDouble(point.heapUsagePct)).append(',')
                        .append("\"qps\":").append(point.queriesPerSec)
                        .append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private String topSqlJson() {
            List<SqlStat> list = new ArrayList<SqlStat>(sqlStats.values());
            list.sort(Comparator.comparingLong(SqlStat::getTotalTimeMs).reversed());
            int limit = Math.min(8, list.size());

            StringBuilder sb = new StringBuilder();
            sb.append("{\"items\":[");
            for (int i = 0; i < limit; i++) {
                SqlStat stat = list.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{")
                        .append("\"sql\":\"").append(escapeJson(stat.sql)).append("\",")
                        .append("\"count\":").append(stat.count.get()).append(',')
                        .append("\"avgMs\":").append(formatDouble(stat.averageMs())).append(',')
                        .append("\"maxMs\":").append(stat.maxMs.get()).append(',')
                        .append("\"errorCount\":").append(stat.errorCount.get())
                        .append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private String slowQueriesJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"items\":[");
            int idx = 0;
            for (SlowQueryEvent event : slowQueries) {
                if (idx++ > 0) {
                    sb.append(',');
                }
                sb.append("{")
                        .append("\"timestamp\":").append(event.timestamp).append(',')
                        .append("\"durationMs\":").append(event.durationMs).append(',')
                        .append("\"status\":\"").append(escapeJson(event.status)).append("\",")
                        .append("\"sql\":\"").append(escapeJson(event.sql)).append("\",")
                        .append("\"errorMessage\":").append(event.errorMessage == null ? "null" : "\"" + escapeJson(event.errorMessage) + "\"")
                        .append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private String sqlTimelineJson() {
            StringBuilder sb = new StringBuilder("{\"points\":[");
            int index = 0;
            for (SqlPoint point : sqlTimeline) {
                if (index++ > 0) sb.append(',');
                String color = point.error ? "error" : point.durationMs >= 10000L ? "red" : point.durationMs >= 3000L ? "yellow" : "green";
                sb.append("{\"timestamp\":").append(point.timestamp)
                        .append(",\"sql\":\"").append(escapeJson(point.sql))
                        .append("\",\"durationMs\":").append(point.durationMs)
                        .append(",\"color\":\"").append(color).append("\"}");
            }
            return sb.append("]}").toString();
        }

        private String normalizeSql(String sql) {
            if (sql == null) {
                return "";
            }
            String normalized = sql.replaceAll("\\s+", " ").trim();
            return truncate(normalized, 180);
        }
    }

    private static final class TrafficMetrics {
        private static final int MAX_URI_STATS = 1000;
        private final AtomicLong totalHits = new AtomicLong();
        private final AtomicLong totalErrors = new AtomicLong();
        private final AtomicLong activeRequests = new AtomicLong();
        private final AtomicLong lastHitsBase = new AtomicLong();
        private final AtomicLong lastErrorsBase = new AtomicLong();
        private volatile long lastHitsRotateNanos = System.nanoTime();
        private volatile long hitsPerSecond;
        private volatile double errorRatePercent;
        private final ConcurrentHashMap<String, TrafficStat> uriStats = new ConcurrentHashMap<String, TrafficStat>();
        private final Deque<HttpErrorEvent> recentErrors = new ConcurrentLinkedDeque<HttpErrorEvent>();
        private final Deque<TrafficSnapshot> timeline = new ConcurrentLinkedDeque<TrafficSnapshot>();

        private void enter(Object request) {
            totalHits.incrementAndGet();
            activeRequests.incrementAndGet();
            TrafficStat stat = getOrCreate(requestUri(request));
            if (stat != null) stat.active.incrementAndGet();
        }

        private void exit() {
            activeRequests.updateAndGet(value -> value > 0 ? value - 1 : 0);
        }

        private void observe(Object request, Object response, long durationNanos, Throwable thrown) {
            String uri = requestUri(request);
            TrafficStat stat = getOrCreate(uri);
            int status = responseStatus(response);
            boolean error = thrown != null || status >= 500;
            if (error) {
                totalErrors.incrementAndGet();
                HttpErrorEvent event = new HttpErrorEvent();
                event.timestamp = System.currentTimeMillis();
                event.uri = uri;
                event.status = status;
                event.message = truncate(thrown == null ? "HTTP " + status : thrown.toString(), 500);
                recentErrors.addFirst(event);
                while (recentErrors.size() > 200) recentErrors.pollLast();
            }
            if (stat != null) {
                stat.observe(durationNanos, error);
                stat.active.updateAndGet(value -> value > 0 ? value - 1 : 0);
            }
        }

        private long rotateHitsPerSecond() {
            long current = totalHits.get();
            long previous = lastHitsBase.getAndSet(current);
            long now = System.nanoTime();
            long elapsedNanos = now - lastHitsRotateNanos;
            lastHitsRotateNanos = now;
            long hits = current - previous;
            long currentErrors = totalErrors.get();
            long errors = currentErrors - lastErrorsBase.getAndSet(currentErrors);
            hitsPerSecond = elapsedNanos <= 0L ? hits : Math.round(hits * (1_000_000_000D / elapsedNanos));
            errorRatePercent = hits <= 0L ? 0D : errors * 100D / hits;
            for (TrafficStat stat : uriStats.values()) {
                stat.rotateHitsPerSecond(elapsedNanos);
            }
            return hitsPerSecond;
        }

        private String json() {
            List<TrafficStat> stats = new ArrayList<TrafficStat>(uriStats.values());
            stats.sort(Comparator.comparingLong(TrafficStat::totalTimeNanos).reversed());
            StringBuilder items = new StringBuilder("[");
            int limit = Math.min(20, stats.size());
            for (int i = 0; i < limit; i++) {
                TrafficStat stat = stats.get(i);
                if (i > 0) items.append(',');
                items.append("{\"uri\":\"").append(escapeJson(stat.uri)).append("\",")
                        .append("\"hits\":").append(stat.hits.get()).append(',')
                        .append("\"active\":").append(stat.active.get()).append(',')
                        .append("\"avgMs\":").append(formatDouble(stat.averageMs())).append(',')
                        .append("\"maxMs\":").append(TimeUnit.NANOSECONDS.toMillis(stat.maxNanos.get())).append(',')
                        .append("\"errors\":").append(stat.errors.get()).append(',')
                        .append("\"errorRatePercent\":").append(formatDouble(stat.errorRatePercent)).append('}');
            }
            items.append(']');
            return "{"
                    + "\"totalHits\":" + totalHits.get() + ","
                    + "\"hitsPerSecond\":" + hitsPerSecond + ","
                    + "\"tps\":" + hitsPerSecond + ","
                    + "\"errorRatePercent\":" + formatDouble(errorRatePercent) + ","
                    + "\"activeRequests\":" + activeRequests.get() + ","
                    + "\"uniqueUsers\":null,"
                    + "\"uniqueUsersStatus\":\"unknown\","
                    + "\"uris\":" + items
                    + "}";
        }

        private TrafficSnapshot snapshot() {
            TrafficSnapshot snapshot = new TrafficSnapshot();
            snapshot.timestamp = System.currentTimeMillis();
            snapshot.tps = hitsPerSecond;
            snapshot.activeRequests = activeRequests.get();
            snapshot.errorRatePercent = errorRatePercent;
            return snapshot;
        }

        private void addTimelinePoint() {
            timeline.addLast(snapshot());
            while (timeline.size() > 180) timeline.pollFirst();
        }

        private String timelineJson() {
            StringBuilder sb = new StringBuilder("{\"points\":[");
            int index = 0;
            for (TrafficSnapshot point : timeline) {
                if (index++ > 0) sb.append(',');
                sb.append("{\"timestamp\":").append(point.timestamp)
                        .append(",\"tps\":").append(point.tps)
                        .append(",\"activeRequests\":").append(point.activeRequests)
                        .append(",\"errorRatePercent\":").append(formatDouble(point.errorRatePercent)).append('}');
            }
            return sb.append("]}").toString();
        }

        private String alertsJson() {
            List<String> alerts = new ArrayList<String>();
            if (CONFIG.alertHitsPerSecond > 0 && hitsPerSecond >= CONFIG.alertHitsPerSecond) {
                alerts.add("{\"type\":\"HITS\",\"scope\":\"global\",\"value\":" + hitsPerSecond + ",\"threshold\":" + CONFIG.alertHitsPerSecond + "}");
            }
            if (CONFIG.alertActiveRequests > 0 && activeRequests.get() >= CONFIG.alertActiveRequests) {
                alerts.add("{\"type\":\"CONCURRENCY\",\"scope\":\"global\",\"value\":" + activeRequests.get() + ",\"threshold\":" + CONFIG.alertActiveRequests + "}");
            }
            if (CONFIG.alertErrorPercent > 0D && errorRatePercent >= CONFIG.alertErrorPercent) {
                alerts.add("{\"type\":\"ERROR_RATE\",\"scope\":\"global\",\"value\":" + formatDouble(errorRatePercent) + ",\"threshold\":" + formatDouble(CONFIG.alertErrorPercent) + "}");
            }
            for (TrafficStat stat : uriStats.values()) {
                if (CONFIG.alertHitsPerSecond > 0 && stat.hitsPerSecond >= CONFIG.alertHitsPerSecond) {
                    alerts.add("{\"type\":\"HITS\",\"scope\":\"" + escapeJson(stat.uri) + "\",\"value\":" + stat.hitsPerSecond + ",\"threshold\":" + CONFIG.alertHitsPerSecond + "}");
                }
                if (CONFIG.alertErrorPercent > 0D && stat.errorRatePercent >= CONFIG.alertErrorPercent) {
                    alerts.add("{\"type\":\"ERROR_RATE\",\"scope\":\"" + escapeJson(stat.uri) + "\",\"value\":" + formatDouble(stat.errorRatePercent) + ",\"threshold\":" + formatDouble(CONFIG.alertErrorPercent) + "}");
                }
            }
            return "{\"status\":\"" + (alerts.isEmpty() ? "OK" : "ALERT") + "\",\"alerts\":[" + String.join(",", alerts) + "]}";
        }

        private String errorsJson() {
            StringBuilder sb = new StringBuilder("{\"items\":[");
            int index = 0;
            for (HttpErrorEvent event : recentErrors) {
                if (index++ > 0) sb.append(',');
                sb.append("{\"timestamp\":").append(event.timestamp)
                        .append(",\"uri\":\"").append(escapeJson(event.uri))
                        .append("\",\"status\":").append(event.status)
                        .append(",\"message\":\"").append(escapeJson(event.message)).append("\"}");
            }
            return sb.append("]}").toString();
        }

        private String requestUri(Object request) {
            if (request == null) return "unknown";
            try {
                Method method = request.getClass().getMethod("getRequestURI");
                Object value = method.invoke(request);
                return truncate(value == null ? "unknown" : String.valueOf(value), 200);
            } catch (Throwable ignored) {
                return "unknown";
            }
        }

        private int responseStatus(Object response) {
            if (response == null) return 0;
            try {
                Method method = response.getClass().getMethod("getStatus");
                Object value = method.invoke(response);
                return value instanceof Number ? ((Number) value).intValue() : 0;
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private TrafficStat getOrCreate(String uri) {
            TrafficStat stat = uriStats.get(uri);
            if (stat == null && uriStats.size() < MAX_URI_STATS) {
                TrafficStat candidate = new TrafficStat(uri);
                TrafficStat existing = uriStats.putIfAbsent(uri, candidate);
                stat = existing == null ? candidate : existing;
            }
            return stat;
        }
    }

    private static final class TrafficStat {
        private final String uri;
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong active = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong lastHitsBase = new AtomicLong();
        private final AtomicLong lastErrorsBase = new AtomicLong();
        private volatile long hitsPerSecond;
        private volatile double errorRatePercent;

        private TrafficStat(String uri) { this.uri = uri; }

        private void observe(long durationNanos, boolean error) {
            hits.incrementAndGet();
            totalNanos.addAndGet(durationNanos);
            updateMax(durationNanos);
            if (error) errors.incrementAndGet();
        }

        private void updateMax(long value) {
            long previous;
            do {
                previous = maxNanos.get();
                if (value <= previous) return;
            } while (!maxNanos.compareAndSet(previous, value));
        }

        private long totalTimeNanos() { return totalNanos.get(); }
        private double averageMs() {
            long count = hits.get();
            return count == 0 ? 0D : (totalNanos.get() / 1_000_000D) / count;
        }

        private void rotateHitsPerSecond(long elapsedNanos) {
            long current = hits.get();
            long previous = lastHitsBase.getAndSet(current);
            long delta = current - previous;
            hitsPerSecond = elapsedNanos <= 0L ? delta : Math.round(delta * (1_000_000_000D / elapsedNanos));
            long currentErrors = errors.get();
            long previousErrors = lastErrorsBase.getAndSet(currentErrors);
            long deltaErrors = currentErrors - previousErrors;
            errorRatePercent = delta <= 0L ? 0D : deltaErrors * 100D / delta;
        }
    }

    private static final class SqlStat {
        private final String sql;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalTimeMs = new AtomicLong();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();

        private SqlStat(String sql) {
            this.sql = sql;
        }

        private void record(long durationMs, boolean error) {
            count.incrementAndGet();
            totalTimeMs.addAndGet(durationMs);
            updateMax(durationMs);
            if (error) {
                errorCount.incrementAndGet();
            }
        }

        private void updateMax(long durationMs) {
            long prev;
            do {
                prev = maxMs.get();
                if (durationMs <= prev) {
                    return;
                }
            } while (!maxMs.compareAndSet(prev, durationMs));
        }

        private long getTotalTimeMs() {
            return totalTimeMs.get();
        }

        private double averageMs() {
            long currentCount = count.get();
            return currentCount == 0 ? 0D : ((double) totalTimeMs.get()) / currentCount;
        }
    }

    private static final class JvmSnapshot {
        private long timestamp;
        private String serviceName = CONFIG.serviceName;
        private double heapUsedMb;
        private double heapMaxMb;
        private double heapUsagePct;
        private double nonHeapUsedMb;
        private int threadCount;
        private int daemonThreadCount;
        private int loadedClassCount;
        private long totalLoadedClassCount;
        private long unloadedClassCount;
        private long gcCount;
        private long gcTimeMs;
        private long uptimeMs;
        private double processCpuLoad;
        private double systemCpuLoad;
        private long queriesPerSec;

        private JvmSnapshot copy() {
            JvmSnapshot copy = new JvmSnapshot();
            copy.timestamp = this.timestamp;
            copy.serviceName = this.serviceName;
            copy.heapUsedMb = this.heapUsedMb;
            copy.heapMaxMb = this.heapMaxMb;
            copy.heapUsagePct = this.heapUsagePct;
            copy.nonHeapUsedMb = this.nonHeapUsedMb;
            copy.threadCount = this.threadCount;
            copy.daemonThreadCount = this.daemonThreadCount;
            copy.loadedClassCount = this.loadedClassCount;
            copy.totalLoadedClassCount = this.totalLoadedClassCount;
            copy.unloadedClassCount = this.unloadedClassCount;
            copy.gcCount = this.gcCount;
            copy.gcTimeMs = this.gcTimeMs;
            copy.uptimeMs = this.uptimeMs;
            copy.processCpuLoad = this.processCpuLoad;
            copy.systemCpuLoad = this.systemCpuLoad;
            copy.queriesPerSec = this.queriesPerSec;
            return copy;
        }
    }

    private static final class SlowQueryEvent {
        private long timestamp;
        private long durationMs;
        private String status;
        private String sql;
        private String errorMessage;
    }

    private static final class HttpErrorEvent {
        private long timestamp;
        private String uri;
        private int status;
        private String message;
    }

    private static final class TrafficSnapshot {
        private long timestamp;
        private long tps;
        private long activeRequests;
        private double errorRatePercent;
    }

    private static final class SqlPoint {
        private final long timestamp;
        private final String sql;
        private final long durationMs;
        private final boolean error;

        private SqlPoint(long timestamp, String sql, long durationMs, boolean error) {
            this.timestamp = timestamp;
            this.sql = sql;
            this.durationMs = durationMs;
            this.error = error;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch == '\n') {
                sb.append("\\n");
            } else if (ch == '\r') {
                sb.append("\\r");
            } else if (ch == '\t') {
                sb.append("\\t");
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.4f", value);
    }
}
