package com.sschoi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class APMLiteAgent {

    private static final Config CONFIG = new Config();
    private static final MetricsStore STORE = new MetricsStore();
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(namedFactory("apmlite-db"));
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, namedFactory("apmlite-scheduler"));

    public static void premain(String agentArgs, Instrumentation inst) {
        CONFIG.apply(agentArgs);
        log("Starting agent for service '" + CONFIG.serviceName + "'");

        initDatabase();
        startJvmSampler();
        startHttpServer();
        installJdbcInstrumentation(inst);

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

                STORE.setSnapshot(snapshot);
                STORE.addTimelinePoint(snapshot);
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
            server.setExecutor(Executors.newCachedThreadPool(namedFactory("apmlite-http")));
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start dashboard on port " + CONFIG.httpPort, e);
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
                            String filledSql = fillParams(sql, params);
                            STORE.recordSqlExecution(filledSql, durationMs, error);

                            if (durationMs >= CONFIG.slowQueryThresholdMs || error != null) {
                                SlowQueryEvent event = new SlowQueryEvent();
                                event.timestamp = System.currentTimeMillis();
                                event.durationMs = durationMs;
                                event.sql = filledSql;
                                event.status = error == null ? "SLOW" : "ERROR";
                                event.errorMessage = error == null ? null : truncate(error.toString(), 500);
                                STORE.addSlowQuery(event);
                                persistSlowQuery(event);

                                if (CONFIG.useLog) {
                                    log("SQL " + event.status + " " + durationMs + "ms :: " + truncate(filledSql, 220));
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
                + ".muted{color:var(--muted)}#timeline{display:flex;align-items:flex-end;gap:6px;height:180px;margin-top:14px}.bar{flex:1;min-width:6px;border-radius:8px 8px 2px 2px;background:linear-gradient(180deg,var(--accent),#2563eb)}"
                + "@media (max-width:920px){.split{grid-template-columns:1fr}.hero{align-items:start;flex-direction:column}}"
                + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"hero\"><div><h1>APMLite Agent</h1><p>Lightweight JVM APM dashboard inspired by Jennifer</p></div><div class=\"muted\">Service <span id=\"service\">-</span></div></div>"
                + "<div class=\"grid\">"
                + "<div class=\"card\"><div class=\"label\">Heap Usage</div><div class=\"value\" id=\"heapPct\">-</div><div class=\"muted\" id=\"heapDetail\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">QPS</div><div class=\"value\" id=\"qps\">-</div><div class=\"muted\">Queries per second from the latest sample</div></div>"
                + "<div class=\"card\"><div class=\"label\">Threads</div><div class=\"value\" id=\"threads\">-</div><div class=\"muted\" id=\"daemonThreads\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">CPU</div><div class=\"value\" id=\"cpu\">-</div><div class=\"muted\" id=\"sysCpu\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">Slow Queries</div><div class=\"value\" id=\"slowCount\">-</div><div class=\"muted\">Total captured slow or error queries</div></div>"
                + "</div>"
                + "<div class=\"split\">"
                + "<div class=\"card\"><div class=\"label\">Heap Timeline</div><div id=\"timeline\"></div></div>"
                + "<div class=\"card\"><div class=\"label\">Top SQL</div><table class=\"table\"><thead><tr><th>SQL</th><th>Count</th><th>Avg</th><th>Max</th></tr></thead><tbody id=\"sqlTop\"></tbody></table></div>"
                + "</div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">Recent Slow Queries</div><table class=\"table\"><thead><tr><th>Time</th><th>Status</th><th>Duration</th><th>SQL</th></tr></thead><tbody id=\"slowRows\"></tbody></table></div>"
                + "</div><script>"
                + "async function load(){const [overview,timeline,top,slow]=await Promise.all([fetch('/api/overview').then(r=>r.json()),fetch('/api/timeline').then(r=>r.json()),fetch('/api/sql/top').then(r=>r.json()),fetch('/api/sql/slow').then(r=>r.json())]);"
                + "document.getElementById('service').textContent=overview.serviceName;"
                + "document.getElementById('heapPct').textContent=overview.heapUsagePct.toFixed(1)+'%';"
                + "document.getElementById('heapDetail').textContent=overview.heapUsedMb.toFixed(1)+' MB / '+overview.heapMaxMb.toFixed(1)+' MB';"
                + "document.getElementById('qps').textContent=overview.queriesPerSec;"
                + "document.getElementById('threads').textContent=overview.threadCount;"
                + "document.getElementById('daemonThreads').textContent='Daemon '+overview.daemonThreadCount;"
                + "document.getElementById('cpu').textContent=(overview.processCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('sysCpu').textContent='System '+(overview.systemCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('slowCount').textContent=overview.slowQueryCount;"
                + "const max=Math.max(...timeline.points.map(p=>p.heapUsagePct),1);document.getElementById('timeline').innerHTML=timeline.points.map(p=>'<div class=\"bar\" title=\"'+p.heapUsagePct.toFixed(1)+'%\" style=\"height:'+Math.max(8,(p.heapUsagePct/max)*100)+'%\"></div>').join('');"
                + "document.getElementById('sqlTop').innerHTML=top.items.map(i=>'<tr><td>'+escapeHtml(i.sql)+'</td><td>'+i.count+'</td><td>'+i.avgMs.toFixed(1)+' ms</td><td>'+i.maxMs+' ms</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No SQL data has been captured yet.</td></tr>';"
                + "document.getElementById('slowRows').innerHTML=slow.items.map(i=>'<tr><td>'+new Date(i.timestamp).toLocaleTimeString()+'</td><td><span class=\"pill '+i.status.toLowerCase()+'\">'+i.status+'</span></td><td>'+i.durationMs+' ms</td><td>'+escapeHtml(i.sql)+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No slow queries have been captured recently.</td></tr>';"
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

        private String normalizeSql(String sql) {
            if (sql == null) {
                return "";
            }
            String normalized = sql.replaceAll("\\s+", " ").trim();
            return truncate(normalized, 180);
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
