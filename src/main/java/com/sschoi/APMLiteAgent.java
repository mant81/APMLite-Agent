package com.sschoi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
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
    private static final ThreadLocal<HttpRequestContext> HTTP_CONTEXT = new ThreadLocal<HttpRequestContext>();
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(namedFactory("apmlite-db"));
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, namedFactory("apmlite-scheduler"));

    public static void recordHttpEnter(Object request) {
        try {
            TRAFFIC.enter(request);
        } catch (Throwable ignored) {
            // Metrics must never interfere with request processing.
        }
    }

    public static void recordHttpExit(Object request, Object response, long durationNanos, Throwable thrown) {
        try {
            TRAFFIC.exit();
            TRAFFIC.observe(request, response, durationNanos, thrown);
        } catch (Throwable ignored) {
            // Metrics must never interfere with request processing.
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        CONFIG.apply(agentArgs);
        log("Starting agent for service '" + CONFIG.serviceName + "'");

        COLLECTOR.start();
        initDatabase();
        startJvmSampler();
        installJdbcInstrumentation(inst);
        installHttpInstrumentation(inst);
        installSpringMvcInstrumentation(inst);
        installWebFluxInstrumentation(inst);
        startHttpServerAsync();

        log("Agent ready. Dashboard starting at http://127.0.0.1:" + CONFIG.httpPort);
    }

    private static void startHttpServerAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                startHttpServer();
            }
        }, "apmlite-http-bootstrap");
        thread.setDaemon(true);
        thread.start();
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
                        builder.method(ElementMatchers.named("prepareStatement")
                                .and(ElementMatchers.takesArgument(0, String.class)))
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

    private static void installSpringMvcInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("org.springframework.web.servlet.FrameworkServlet"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(ElementMatchers.named("service")
                                .and(ElementMatchers.takesArguments(2)))
                                .intercept(Advice.to(HttpServiceAdvice.class)))
                .installOn(inst);
    }

    private static void installWebFluxInstrumentation(Instrumentation inst) {
        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("org.springframework.web.reactive.DispatcherHandler"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(ElementMatchers.named("handle")
                                .and(ElementMatchers.takesArguments(1)))
                                .intercept(Advice.to(WebFluxHandleAdvice.class)))
                .installOn(inst);
    }

    public static class HttpServiceAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object onEnter(@Advice.Argument(0) Object request) {
            return beginHttpRequest(request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter Object invocation,
                                  @Advice.Argument(1) Object response,
                                  @Advice.Thrown Throwable thrown) {
            endHttpRequest(invocation, response, thrown);
        }
    }

    public static Object beginHttpRequest(Object request) {
        HttpRequestContext context = HTTP_CONTEXT.get();
        if (context == null) {
            context = new HttpRequestContext(request, System.nanoTime());
            HTTP_CONTEXT.set(context);
            recordHttpEnter(request);
        }
        context.depth++;
        return context;
    }

    public static void endHttpRequest(Object invocation, Object response, Throwable thrown) {
        if (!(invocation instanceof HttpRequestContext)) return;
        HttpRequestContext context = (HttpRequestContext) invocation;
        if (--context.depth <= 0) {
            HTTP_CONTEXT.remove();
            recordHttpExit(context.request, response, System.nanoTime() - context.startedAt, thrown);
        }
    }

    private static final class HttpRequestContext {
        private final Object request;
        private final long startedAt;
        private int depth;

        private HttpRequestContext(Object request, long startedAt) {
            this.request = request;
            this.startedAt = startedAt;
        }
    }

    public static class WebFluxHandleAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long onEnter(@Advice.Argument(0) Object exchange) {
            APMLiteAgent.recordHttpEnter(exchange);
            return System.nanoTime();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter long startedAt,
                                  @Advice.Argument(0) Object exchange,
                                  @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable thrown) {
            if (thrown != null || result == null) {
                APMLiteAgent.recordHttpExit(exchange, webFluxResponse(exchange), System.nanoTime() - startedAt, thrown);
                return;
            }
            try {
                result = wrapReactiveResult(result, exchange, startedAt);
            } catch (Throwable ignored) {
                APMLiteAgent.recordHttpExit(exchange, webFluxResponse(exchange), System.nanoTime() - startedAt, null);
            }
        }
    }

    public static Object wrapReactiveResult(final Object publisher, final Object exchange, final long startedAt) throws Exception {
        ClassLoader loader = publisher.getClass().getClassLoader();
        Class<?> publisherType = Class.forName("org.reactivestreams.Publisher", false, loader);
        Class<?> monoType = Class.forName("reactor.core.publisher.Mono", false, loader);
        Object mono = monoType.getMethod("from", publisherType).invoke(null, publisher);
        final java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Object withError = monoType.getMethod("doOnError", java.util.function.Consumer.class)
                .invoke(mono, new java.util.function.Consumer<Object>() {
                    @Override public void accept(Object value) {
                        if (value instanceof Throwable) error.set((Throwable) value);
                    }
                });
        return monoType.getMethod("doFinally", java.util.function.Consumer.class)
                .invoke(withError, new java.util.function.Consumer<Object>() {
                    @Override public void accept(Object signal) {
                        APMLiteAgent.recordHttpExit(exchange, webFluxResponse(exchange), System.nanoTime() - startedAt, error.get());
                    }
                });
    }

    public static Object webFluxResponse(Object exchange) {
        if (exchange == null) return null;
        try {
            return exchange.getClass().getMethod("getResponse").invoke(exchange);
        } catch (Throwable ignored) {
            return null;
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
                + ":root{color-scheme:light;--bg:#f4f7fb;--panel:#fff;--panel2:#f8fafc;--text:#17243a;--muted:#68778d;--accent:#2563eb;--warn:#a16207;--bad:#be123c;--line:#e2e8f0;}"
                + "*{box-sizing:border-box}body{margin:0;font-family:'Segoe UI',Arial,sans-serif;background:var(--bg);color:var(--text);line-height:1.5}"
                + ".wrap{max-width:1440px;margin:0 auto;padding:28px 32px 56px}.hero{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}"
                + "h1{margin:0;font-size:28px;letter-spacing:-.03em}p{margin:4px 0 0;color:var(--muted)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}"
                + ".card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px;box-shadow:0 2px 8px rgba(15,23,42,.04);min-width:0}"
                + ".label{font-size:12px;letter-spacing:.04em;color:var(--muted);font-weight:650}.value{font-size:30px;line-height:1.2;font-weight:700;margin-top:8px;letter-spacing:-.03em}"
                + ".split{display:grid;grid-template-columns:2fr 1.2fr;gap:14px;margin-top:14px}.table{width:100%;border-collapse:collapse;margin-top:10px}"
                + ".table th,.table td{text-align:left;padding:11px 10px;border-bottom:1px solid var(--line);font-size:13px;vertical-align:top}.table th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.04em;white-space:nowrap}.table td{overflow-wrap:anywhere}.table tr:last-child td{border-bottom:0}"
                + ".pill{display:inline-block;padding:4px 8px;border-radius:999px;font-size:12px;font-weight:700}.pill.slow{background:rgba(245,158,11,.14);color:var(--warn)}.pill.error{background:rgba(251,113,133,.14);color:var(--bad)}"
                + ".pill.ok{background:#dcfce7;color:#166534}.metric-list{display:flex;gap:18px;flex-wrap:wrap;margin-top:12px}.metric-list strong{display:block;font-size:22px}.sql-heatmap{display:grid;grid-template-columns:48px minmax(0,1fr);gap:8px;margin-top:16px}.sql-yaxis{display:flex;flex-direction:column;justify-content:space-between;height:210px;padding:0 0 18px;text-align:right;color:var(--muted);font-size:10px}.sql-plot-wrap{min-width:0}.sql-points{height:210px;position:relative;overflow:hidden;border-left:1px solid #cbd5e1;border-bottom:1px solid #cbd5e1;background:repeating-linear-gradient(to top,transparent 0,transparent calc(25% - 1px),#e8edf4 calc(25% - 1px),#e8edf4 25%),repeating-linear-gradient(to right,transparent 0,transparent calc(10% - 1px),#eef2f7 calc(10% - 1px),#eef2f7 10%)}.sql-point{position:absolute;width:11px;height:11px;border:2px solid white;border-radius:50%;transform:translate(-50%,50%);cursor:pointer;box-shadow:0 1px 4px #33415555;transition:left 2.2s linear,bottom .35s ease,transform .12s,box-shadow .12s}.sql-point:hover,.sql-point:focus,.sql-point.selected{z-index:2;transform:translate(-50%,50%) scale(1.55);box-shadow:0 0 0 3px #2563eb33;outline:0}.sql-point.green{background:#16a34a}.sql-point.yellow{background:#eab308}.sql-point.red{background:#dc2626}.sql-point.error{background:#9333ea}.sql-xaxis{display:flex;justify-content:space-between;color:var(--muted);font-size:10px;padding-top:5px}.sql-pick{margin-top:12px;padding:10px 12px;background:#f8fafc;border:1px solid var(--line);border-radius:8px;font-size:12px}.sql-pick strong{color:var(--accent);margin-right:10px}.sql-pick-code{display:block;margin-top:4px;color:var(--text);overflow-wrap:anywhere}.trend-points{display:flex;gap:8px;align-items:flex-end;min-height:135px;overflow:auto;margin-top:12px}.trend-item{display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:125px;min-width:30px;font-size:10px;color:var(--muted)}.trend-item i{display:block;width:22px;min-height:4px;border-radius:5px 5px 2px 2px;background:linear-gradient(180deg,var(--accent),#2563eb)}.trend-item b{font-size:10px;color:var(--text);margin-bottom:3px;font-weight:600}"
                + ".muted{color:var(--muted)}#timeline{display:flex;align-items:flex-end;gap:6px;height:180px;margin-top:14px}.bar{flex:1;min-width:6px;border-radius:5px 5px 2px 2px;background:linear-gradient(180deg,#60a5fa,#2563eb)}"
                + ".section-title{font-size:17px;font-weight:700;margin:28px 0 10px;color:var(--text)}.nav{display:flex;gap:6px;flex-wrap:wrap;align-items:center}.nav a{color:#334155;text-decoration:none;background:#f1f5f9;border:1px solid var(--line);padding:7px 11px;border-radius:7px;font-size:12px;font-weight:600}.nav a:hover{background:#e2e8f0}.metric-list{gap:30px}.metric-list strong{font-size:24px;margin-top:3px}.trend-item i{background:linear-gradient(180deg,#60a5fa,#2563eb)}input{font:inherit;max-width:220px}"
                + ".traffic-kpis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin:0 0 24px}.traffic-kpi{border-top:4px solid var(--accent);min-height:128px;padding:20px 22px}.traffic-kpi:nth-child(2){border-top-color:#0f766e}.traffic-kpi:nth-child(3){border-top-color:#7c3aed}.traffic-kpi:nth-child(4){border-top-color:#dc2626}.traffic-kpi:nth-child(5){border-top-color:#d97706}.traffic-kpi .label{font-size:13px}.traffic-kpi strong{display:block;font-size:40px;line-height:1.15;margin-top:12px;letter-spacing:-.04em;font-variant-numeric:tabular-nums}.traffic-kpi .unit{font-size:14px;font-weight:600;color:var(--muted);margin-left:5px}"
                + ".traffic-flow{margin:-8px 0 24px;padding:18px 22px}.flow-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.flow-head strong{font-size:15px}.flow-head .muted{margin-left:10px}.combo-badge,.load-badge{padding:5px 9px;border-radius:999px;background:#eff6ff;color:#1d4ed8;font-size:11px;font-weight:800;letter-spacing:.04em}.load-badge{background:#dcfce7;color:#166534}.load-badge.medium{background:#fef3c7;color:#92400e}.load-badge.high{background:#fee2e2;color:#b91c1c;animation:hot-glow .8s ease-in-out infinite alternate}.combo-badge.combo-hit{background:#dbeafe;color:#1e40af}.traffic-kpi#tpsKpi{position:relative;isolation:isolate;overflow:hidden;background:linear-gradient(130deg,#fff,#eff6ff);transition:box-shadow .3s,background .3s}.traffic-kpi#tpsKpi:before{content:'';position:absolute;z-index:0;inset:-45%;background:radial-gradient(ellipse at center,var(--tps-glow,rgba(59,130,246,.35)),transparent 62%);filter:blur(15px);opacity:.7;animation:tps-ambient 3s ease-in-out infinite alternate;pointer-events:none}.traffic-kpi#tpsKpi .label,.traffic-kpi#tpsKpi strong{position:relative;z-index:1}.traffic-kpi#tpsKpi.tps-medium{--tps-glow:rgba(245,158,11,.42);box-shadow:0 8px 28px rgba(245,158,11,.16)}.traffic-kpi#tpsKpi.tps-high{--tps-glow:rgba(239,68,68,.52);box-shadow:0 8px 30px rgba(239,68,68,.22)}@keyframes tps-ambient{from{transform:translate(-18%,-4%) scale(.92);opacity:.48}to{transform:translate(18%,4%) scale(1.08);opacity:.9}}.flow-track:after{content:'';position:absolute;z-index:1;top:0;bottom:0;left:-45%;width:45%;background:linear-gradient(90deg,transparent,rgba(22,163,74,.06),rgba(255,255,255,.92),rgba(22,163,74,.06),transparent);filter:blur(1px);animation:lane-flow-in var(--flow-in-speed,var(--flow-speed,2.8s)) linear infinite;pointer-events:none}.flow-lane.flow-idle .packet,.flow-lane.flow-idle .flow-track:after{animation-play-state:paused!important;opacity:.16}.flow-lane.out .flow-track:after{left:auto;right:-45%;animation-name:lane-flow-out;animation-duration:var(--flow-out-speed,var(--flow-speed,2.8s));background:linear-gradient(90deg,transparent,rgba(15,118,110,.05),rgba(255,255,255,.92),rgba(15,118,110,.05),transparent)}.flow-stage.level-medium .flow-track:after{background:linear-gradient(90deg,transparent,rgba(217,119,6,.08),#fff,rgba(217,119,6,.08),transparent)}.flow-stage.level-high .flow-track:after{background:linear-gradient(90deg,transparent,rgba(220,38,38,.12),#fff,rgba(220,38,38,.12),transparent)}@keyframes lane-flow-in{to{left:100%}}@keyframes lane-flow-out{to{right:100%}}.flow-stage{display:grid;grid-template-columns:112px minmax(80px,1fr) 112px;align-items:center;gap:14px;margin-top:14px}.flow-node{border:1px solid #cbd5e1;border-radius:9px;text-align:center;padding:12px 8px;background:#f8fafc;font-weight:700;transition:background .25s,color .25s,box-shadow .25s}.flow-node.was{color:white;background:#16a34a;border-color:#16a34a;box-shadow:0 4px 12px #16a34a33}.flow-lanes{display:grid;gap:8px}.flow-lane{display:grid;grid-template-columns:42px minmax(0,1fr) 72px;align-items:center;gap:8px;font-size:11px;font-weight:700}.flow-track{height:22px;background:#dcfce7;border-radius:999px;position:relative;overflow:hidden;transition:background .25s}.flow-lane.out .flow-track{background:#ccfbf1}.packet{position:absolute;left:-8px;top:7px;width:8px;height:8px;border-radius:50%;background:#16a34a;box-shadow:0 0 9px #4ade80;animation:packet-in var(--flow-in-speed,var(--flow-speed,2.8s)) linear infinite;transition:background .25s,box-shadow .25s}.flow-lane.out .packet{background:#0f766e;box-shadow:0 0 9px #5eead4;animation-name:packet-out;animation-duration:var(--flow-out-speed,var(--flow-speed,2.8s))}.flow-stage.level-medium .flow-node.was{background:#d97706;border-color:#d97706;box-shadow:0 0 18px #f59e0b66}.flow-stage.level-medium .flow-track{background:#fef3c7}.flow-stage.level-medium .packet{background:#d97706;box-shadow:0 0 12px #fbbf24}.flow-stage.level-high .flow-node.was{background:#dc2626;border-color:#b91c1c;box-shadow:0 0 24px #ef444488;animation:hot-glow .55s ease-in-out infinite alternate}.flow-stage.level-high .flow-track,.flow-stage.level-high .flow-lane.out .flow-track{background:#fee2e2}.flow-stage.level-high .packet,.flow-stage.level-high .flow-lane.out .packet{width:10px;height:10px;top:6px;background:#dc2626;box-shadow:0 0 16px #ef4444;animation-duration:var(--flow-in-speed,var(--flow-speed,2.8s))}@keyframes hot-glow{from{filter:saturate(1);box-shadow:0 0 8px #ef444455}to{filter:saturate(1.6);box-shadow:0 0 22px #ef444499}}.packet:nth-child(2){animation-delay:-.35s}.packet:nth-child(3){animation-delay:-.7s}.packet:nth-child(4){animation-delay:-1.05s}.packet:nth-child(5){animation-delay:-1.4s}.packet:nth-child(6){animation-delay:-1.75s}@keyframes packet-in{from{left:-8px;opacity:0}12%{opacity:1}to{left:calc(100% + 8px);opacity:.2}}@keyframes packet-out{from{left:calc(100% + 8px);opacity:0}12%{opacity:1}to{left:-8px;opacity:.2}}.flow-stage.idle .packet,.flow-stage.level-idle .packet,.flow-stage.idle .flow-track:after,.flow-stage.level-idle .flow-track:after{animation-play-state:paused!important;opacity:.2}.traffic-kpi.punch{animation:combo-punch .7s cubic-bezier(.2,.8,.25,1);box-shadow:0 8px 24px rgba(37,99,235,.24)}@keyframes combo-punch{0%,100%{transform:scale(1)}12%{transform:scale(var(--punch-scale,1.08))}25%{transform:scale(.99)}42%{transform:scale(1.05)}55%{transform:scale(.995)}70%{transform:scale(1.025)}85%{transform:scale(1)}}@media(max-width:560px){.traffic-flow{padding:14px}.flow-stage{grid-template-columns:72px minmax(80px,1fr) 72px;gap:7px}.flow-node{font-size:11px;padding:10px 4px}.flow-lane{grid-template-columns:28px minmax(0,1fr) 56px;gap:5px}.flow-head .muted{display:block;margin-left:0}}@media(prefers-reduced-motion:reduce){.flow-stage.level-high .flow-node.was,.traffic-kpi.punch,.load-badge.high{animation:none!important;transition:none!important}.traffic-kpi#tpsKpi:before{animation-duration:4s!important}}"
                + ".split{grid-template-columns:1fr 1fr}.table{display:table}"
                + "@media (max-width:920px){.split{grid-template-columns:1fr}.hero{align-items:start;flex-direction:column}.wrap{padding:20px}.table{display:block;overflow-x:auto;white-space:nowrap}.traffic-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}}@media (max-width:560px){.wrap{padding:14px 12px 36px}h1{font-size:24px}.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.card{padding:14px}.value{font-size:24px}.metric-list{gap:18px}.nav a{padding:6px 8px}.traffic-kpis{gap:8px}.traffic-kpi{min-height:106px;padding:14px}.traffic-kpi strong{font-size:30px}}"
                + ":root{color-scheme:light;--bg:#eef4f1;--panel:#fff;--panel2:#f7faf8;--text:#18343b;--muted:#657b7c;--accent:#087e8b;--warn:#a66a16;--bad:#bd4540;--line:#dbe6e0}.wrap{max-width:1480px;padding:30px 34px 60px}body{background:radial-gradient(circle at 8% 0%,#e3f1eb 0,transparent 28%),var(--bg);color:var(--text)}.hero{padding:8px 2px 20px;border-bottom:1px solid #d4e2dc;margin-bottom:20px}.hero>div:first-child{display:flex;align-items:center;gap:14px}.hero>div:first-child:before{content:'A';display:grid;place-items:center;width:44px;height:44px;flex:none;border-radius:14px;background:linear-gradient(145deg,#087e8b,#155e63);color:#fff;font-size:21px;font-weight:850;box-shadow:0 7px 16px rgba(8,126,139,.2)}.hero h1{font-size:27px;letter-spacing:-.04em}.hero p{font-size:13px}.hero>.muted{padding:9px 13px;border:1px solid var(--line);border-radius:999px;background:rgba(255,255,255,.72);font-size:12px}.card{border-color:var(--line);border-radius:15px;box-shadow:0 7px 24px rgba(24,52,59,.045)}.nav{gap:7px}.nav a{background:#f5f9f7;border-color:#dce8e2;color:#315158;border-radius:999px;padding:8px 13px;transition:background .2s,border-color .2s,color .2s}.nav a:hover{background:#e2f2ed;border-color:#a9d5c7;color:#075d64}.section-title{display:flex;align-items:center;gap:10px;font-size:16px;font-weight:750;letter-spacing:-.01em;margin:27px 0 11px}.section-title:before{content:'';width:8px;height:18px;border-radius:4px;background:linear-gradient(180deg,#10a3a0,#087e8b)}.traffic-kpi{border:1px solid var(--line)!important;border-left:4px solid var(--accent)!important;border-radius:14px;min-height:128px;padding:18px 20px;box-shadow:0 5px 18px rgba(24,52,59,.035)}.traffic-kpi:nth-child(2){border-left-color:#238b83!important}.traffic-kpi:nth-child(3){border-left-color:#547ca5!important}.traffic-kpi:nth-child(4){border-left-color:#bd4540!important}.traffic-kpi:nth-child(5){border-left-color:#a66a16!important}.traffic-kpi .label{font-size:12px;font-weight:700;letter-spacing:.01em}.traffic-kpi strong{font-size:38px;margin-top:10px}.traffic-kpi#tpsKpi{background:linear-gradient(140deg,#fff 12%,#f0faf7 100%)}.traffic-kpi#tpsKpi.tps-low{--tps-glow:rgba(8,126,139,.28)}.traffic-kpi#tpsKpi.tps-idle{--tps-glow:rgba(8,126,139,.18)}.traffic-flow{margin:-8px 0 24px;padding:20px 22px;border-left:4px solid #168f88;background:linear-gradient(115deg,#fff 0%,#f8fcfa 100%)}.flow-head strong{font-size:14px;letter-spacing:.01em}.flow-head .muted{font-size:11px}.flow-node{border-radius:12px;background:#f2f7f5;border-color:#d9e7e0;color:#315158;letter-spacing:.08em;font-size:11px}.flow-node.was{background:#087e8b;border-color:#087e8b;box-shadow:0 5px 18px rgba(8,126,139,.2)}.flow-track{height:24px;border:1px solid rgba(25,110,91,.07);background:#e6f4ed}.flow-lane.out .flow-track{background:#e4f4f2}.flow-stage.level-medium .flow-track{background:#fbf1d9}.flow-stage.level-high .flow-track,.flow-stage.level-high .flow-lane.out .flow-track{background:#fae7e4}.flow-stage.level-medium .flow-node.was{background:#bc7c20;border-color:#bc7c20;box-shadow:0 5px 18px rgba(188,124,32,.2)}.flow-stage.level-high .flow-node.was{background:#bd4540;border-color:#bd4540;box-shadow:0 5px 20px rgba(189,69,64,.24)}.flow-stage.level-medium .packet{background:#a66a16}.flow-stage.level-high .packet,.flow-stage.level-high .flow-lane.out .packet{background:#bd4540;box-shadow:0 0 14px rgba(189,69,64,.65)}.combo-badge{background:#eaf3f1;color:#315b5b}.combo-badge.combo-hit{background:#dff3ec;color:#087e66}.load-badge{background:#e7f4ee;color:#247354}.load-badge.medium{background:#fbf0d9;color:#8a5b11}.load-badge.high{background:#fae4e1;color:#a93632}.table th{background:#f7faf8;border-bottom-color:#dbe6e0}.table td{color:#304c50}.sql-points{border-color:#c9d9d2;background:repeating-linear-gradient(to top,transparent 0,transparent calc(25% - 1px),#dfe9e4 calc(25% - 1px),#dfe9e4 25%),repeating-linear-gradient(to right,transparent 0,transparent calc(10% - 1px),#e6eeea calc(10% - 1px),#e6eeea 10%)}.sql-point{box-shadow:0 2px 7px rgba(32,68,58,.22)}.sql-point:hover,.sql-point:focus,.sql-point.selected{box-shadow:0 0 0 3px rgba(8,126,139,.18),0 2px 7px rgba(32,68,58,.2)}.sql-pick{background:#f4f9f6;border-color:#dbe6e0;border-radius:10px}.sql-pick strong{color:#087e8b}#timeline .bar{background:linear-gradient(180deg,#54b8a1,#168f88)}.trend-item i{background:linear-gradient(180deg,#54b8a1,#168f88)}input:focus{outline:2px solid rgba(8,126,139,.28);border-color:#168f88!important}a:focus-visible,button:focus-visible{outline:3px solid rgba(8,126,139,.35);outline-offset:2px}.flow-stage.level-high .flow-lane.out .packet{animation-duration:var(--flow-out-speed,var(--flow-speed,2.8s))}@media(max-width:920px){.wrap{padding:22px 20px 44px}}@media(max-width:560px){.wrap{padding:18px 12px 36px}.hero{align-items:flex-start}.hero>.muted{font-size:11px}.hero>div:first-child:before{width:38px;height:38px}.traffic-kpi strong{font-size:29px}}"
                + ".sql-click{display:block;width:360px;max-width:100%;padding:0;border:0;background:none;color:#087e8b;text-align:left;text-decoration:underline;text-decoration-style:dotted;text-underline-offset:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer;font:inherit}.sql-modal{width:min(960px,calc(100vw - 32px));max-height:min(82vh,760px);padding:0;border:1px solid var(--line);border-radius:16px;background:var(--panel);color:var(--text);box-shadow:0 24px 80px rgba(14,42,44,.28)}.sql-modal::backdrop{background:rgba(20,40,42,.48);backdrop-filter:blur(3px)}.sql-modal-head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 20px;border-bottom:1px solid var(--line)}.sql-modal-head h2{margin:3px 0 0;font-size:18px}.sql-modal-close{border:1px solid var(--line);border-radius:9px;padding:8px 12px;background:#f5f9f7;color:var(--text);cursor:pointer}.sql-modal pre{margin:0;padding:20px;max-height:calc(82vh - 90px);overflow-y:auto;overflow-x:hidden;scrollbar-gutter:stable;white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace}"
                + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"hero\"><div><h1>APMLite 모니터링</h1><p>JVM · WAS · SQL 실시간 상태</p></div><div class=\"muted\">서비스 <strong id=\"service\">-</strong>　<span id=\"healthBadge\" class=\"pill\">확인 중</span></div></div>"
                + "<div class=\"card\" style=\"margin-bottom:16px;display:flex;gap:10px;align-items:center;justify-content:space-between;flex-wrap:wrap\"><div class=\"nav\"><a href=\"#wasTraffic\">WAS 트래픽</a><a href=\"#overview\">JVM 개요</a><a href=\"#sqlPanel\">SQL 분석</a><a href=\"#errorPanel\">오류</a><a href=\"#collectorPanel\">수집기</a></div><span class=\"muted\">2초마다 갱신</span></div><div class=\"section-title\">WAS 실시간 지표</div><div id=\"wasTraffic\" class=\"traffic-kpis\"><div class=\"card traffic-kpi\" id=\"tpsKpi\"><div class=\"label\">TPS</div><strong><span id=\"trafficTps\">-</span><span class=\"unit\">req/s</span></strong></div><div class=\"card traffic-kpi\"><div class=\"label\">현재 처리 중인 요청</div><strong><span id=\"trafficActive\">-</span><span class=\"unit\">건</span></strong></div><div class=\"card traffic-kpi\"><div class=\"label\">누적 요청</div><strong><span id=\"trafficHits\">-</span><span class=\"unit\">건</span></strong></div><div class=\"card traffic-kpi\"><div class=\"label\">오류율</div><strong><span id=\"trafficErrorRate\">-</span></strong></div><div class=\"card traffic-kpi\"><div class=\"label\">힙 사용률</div><strong><span id=\"heapPct\">-</span></strong><div class=\"muted\" id=\"heapDetail\">-</div></div></div><div class=\"traffic-flow card\"><div class=\"flow-head\"><div><strong>실시간 트래픽 흐름</strong><span class=\"muted\">요청 유입 · 응답 처리 · 참고 기준: 100 TPS 주의 / 300 TPS 위험</span></div><span id=\"trafficLoad\" class=\"load-badge\">IDLE</span><span id=\"trafficCombo\" class=\"combo-badge\">대기 중</span></div><div id=\"flowStage\" class=\"flow-stage level-idle\"><div class=\"flow-node\">CLIENTS</div><div class=\"flow-lanes\"><div class=\"flow-lane in\"><span>IN</span><div class=\"flow-track\"><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i></div><strong id=\"flowInRate\">0/s</strong></div><div class=\"flow-lane out\"><span>OUT</span><div class=\"flow-track\"><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i><i class=\"packet\"></i></div><strong id=\"flowOutRate\">0/s</strong></div></div><div class=\"flow-node was\">WAS</div></div></div><div id=\"overview\" class=\"section-title\">JVM 상태 및 메모리</div>"
                + "<div class=\"grid\">"
                + "<div class=\"card\"><div class=\"label\">QPS</div><div class=\"value\" id=\"qps\">-</div><div class=\"muted\">최근 표본 기준 초당 SQL 실행 건수</div></div>"
                + "<div class=\"card\"><div class=\"label\">스레드</div><div class=\"value\" id=\"threads\">-</div><div class=\"muted\" id=\"daemonThreads\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">CPU</div><div class=\"value\" id=\"cpu\">-</div><div class=\"muted\" id=\"sysCpu\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">느린 SQL</div><div class=\"value\" id=\"slowCount\">-</div><div class=\"muted\">수집한 느린 SQL 및 오류 SQL</div></div>"
                + "<div class=\"card\"><div class=\"label\">GC</div><div class=\"value\" id=\"gcCount\">-</div><div class=\"muted\" id=\"gcTime\">-</div></div>"
                + "<div class=\"card\"><div class=\"label\">에이전트 상태</div><div class=\"value\" id=\"healthState\">-</div><div class=\"muted\" id=\"healthAge\">-</div></div>"
                + "</div>"
                + "<div class=\"section-title\">URI별 요청 상세</div><div id=\"trafficTablePanel\" class=\"card\" style=\"margin-top:14px\"><div class=\"label\">HTTP 요청 상세</div><input id=\"uriFilter\" placeholder=\"URI 검색\" style=\"float:right;background:var(--panel2);border:1px solid rgba(154,176,211,.3);color:var(--text);padding:6px 9px;border-radius:8px\"><table class=\"table\"><thead><tr><th>URI</th><th>요청</th><th>평균</th><th>P95</th><th>최대</th><th>오류</th></tr></thead><tbody id=\"trafficRows\"></tbody></table></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">WAS 트래픽 추이</div><div class=\"muted\">최근 TPS와 동시 처리 추이 · 숫자는 TPS</div><div id=\"trafficTrend\" class=\"trend-points\"></div></div>"
                + "<div class=\"section-title\">알림 센터</div><div id=\"alertsPanel\" class=\"card\" style=\"margin-top:14px\"><div class=\"label\">활성 알림</div><table class=\"table\"><thead><tr><th>유형</th><th>범위</th><th>현재값</th><th>기준</th></tr></thead><tbody id=\"alertRows\"></tbody></table></div>"
                + "<div class=\"split\">"
                + "<div class=\"card\"><div class=\"label\">힙 사용 추이</div><div id=\"timeline\"></div></div>"
                + "<div class=\"card\"><div class=\"label\">상위 SQL</div><table class=\"table\"><thead><tr><th>SQL</th><th>건수</th><th>평균</th><th>최대</th></tr></thead><tbody id=\"sqlTop\"></tbody></table></div>"
                + "</div>"
                + "<div class=\"section-title\">SQL · 오류 분석</div><div id=\"sqlPanel\" class=\"card\" style=\"margin-top:14px\"><div class=\"label\">최근 느린 SQL</div><table class=\"table\"><thead><tr><th>시각</th><th>상태</th><th>실행시간</th><th>SQL</th></tr></thead><tbody id=\"slowRows\"></tbody></table></div>"
                + "<div id=\"errorPanel\" class=\"card\" style=\"margin-top:14px\"><div class=\"label\">최근 예외 · HTTP 오류</div><table class=\"table\"><thead><tr><th>시각</th><th>URI</th><th>상태</th><th>메시지</th></tr></thead><tbody id=\"errorRows\"></tbody></table></div>"
                + "<div class=\"split\"><div class=\"card\"><div class=\"label\">SQL 실행 히트맵</div><div class=\"muted\">최근 5분을 2초마다 이동 표시합니다. 가로축은 시각, 세로축은 실행시간(ms)이며 점을 가리키거나 선택하면 SQL이 표시됩니다.</div><div class=\"sql-heatmap\"><div id=\"sqlYAxis\" class=\"sql-yaxis\"></div><div class=\"sql-plot-wrap\"><div id=\"sqlPoints\" class=\"sql-points\" role=\"img\" aria-label=\"SQL 실행 시간 히트맵\"></div><div id=\"sqlXAxis\" class=\"sql-xaxis\"></div></div></div><div id=\"sqlPick\" class=\"sql-pick\" aria-live=\"polite\">실행 점에 마우스를 올리거나 키보드로 선택하세요.</div></div><div id=\"collectorPanel\" class=\"card\"><div class=\"label\">수집기</div><div class=\"metric-list\"><div><span class=\"muted\">대기열</span><strong id=\"collectorQueue\">-</strong></div><div><span class=\"muted\">폐기</span><strong id=\"collectorDropped\">-</strong></div><div><span class=\"muted\">전송</span><strong id=\"collectorSent\">-</strong></div></div></div></div>"
                + "<div class=\"card\" style=\"margin-top:14px\"><div class=\"label\">수집기 이력</div><table class=\"table\"><thead><tr><th>구간</th><th>서비스</th><th>유형</th><th>건수</th><th>오류</th><th>최대</th></tr></thead><tbody id=\"collectorRows\"></tbody></table></div>"
                + "<dialog id=\"sqlModal\" class=\"sql-modal\" aria-labelledby=\"sqlModalTitle\"><div class=\"sql-modal-head\"><div><div class=\"label\">SQL</div><h2 id=\"sqlModalTitle\">Full SQL</h2></div><button id=\"sqlModalClose\" type=\"button\" class=\"sql-modal-close\">Close</button></div><pre id=\"sqlModalText\"></pre></dialog>"
                + "</div><script>"
                + "async function load(){const [overview,timeline,top,slow,traffic,alerts,sqlTimeline,collector,errors,trafficTimeline,collectorSummary,health]=await Promise.all([fetch('/api/overview').then(r=>r.json()),fetch('/api/timeline').then(r=>r.json()),fetch('/api/sql/top').then(r=>r.json()),fetch('/api/sql/slow').then(r=>r.json()),fetch('/api/traffic').then(r=>r.json()),fetch('/api/alerts').then(r=>r.json()),fetch('/api/sql/timeline').then(r=>r.json()),fetch('/api/collector/status').then(r=>r.json()),fetch('/api/traffic/errors').then(r=>r.json()),fetch('/api/traffic/timeline').then(r=>r.json()),fetch('/api/collector/summary').then(r=>r.json()),fetch('/api/health').then(r=>r.json())]);"
                + "document.getElementById('service').textContent=overview.serviceName;"
                + "document.getElementById('heapPct').textContent=overview.heapUsagePct.toFixed(1)+'%';"
                + "document.getElementById('heapDetail').textContent=overview.heapUsedMb.toFixed(1)+' MB / '+overview.heapMaxMb.toFixed(1)+' MB';"
                + "document.getElementById('qps').textContent=overview.queriesPerSec;"
                + "document.getElementById('threads').textContent=overview.threadCount;"
                + "document.getElementById('daemonThreads').textContent='데몬 '+overview.daemonThreadCount;"
                + "document.getElementById('cpu').textContent=(overview.processCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('sysCpu').textContent='System '+(overview.systemCpuLoad*100).toFixed(1)+'%';"
                + "document.getElementById('slowCount').textContent=overview.slowQueryCount;"
                + "document.getElementById('gcCount').textContent=overview.gcCount;document.getElementById('gcTime').textContent=overview.gcTimeMs+' ms total';document.getElementById('healthState').textContent=health.status;document.getElementById('healthState').className=health.status==='UP'?'pill ok':'pill error';document.getElementById('healthBadge').textContent=health.status;document.getElementById('healthBadge').className=health.status==='UP'?'pill ok':'pill error';document.getElementById('healthAge').textContent='Sample age '+health.sampleAgeMs+' ms';"
                + "updateTrafficPulse(traffic.tps,traffic.responsesPerSecond,trafficTimeline.points,traffic.activeRequests);document.getElementById('trafficTps').textContent=traffic.tps;document.getElementById('trafficActive').textContent=traffic.activeRequests;document.getElementById('trafficHits').textContent=traffic.totalHits.toLocaleString();document.getElementById('trafficErrorRate').textContent=traffic.errorRatePercent.toFixed(2)+'%';"
                + "const uriFilter=(document.getElementById('uriFilter').value||'').toLowerCase();document.getElementById('trafficRows').innerHTML=traffic.uris.filter(i=>i.uri.toLowerCase().includes(uriFilter)).map(i=>'<tr><td>'+escapeHtml(i.uri)+'</td><td>'+i.hits+'</td><td>'+i.avgMs.toFixed(1)+' ms</td><td>'+i.p95Ms.toFixed(1)+' ms</td><td>'+i.maxMs+' ms</td><td>'+i.errors+' ('+i.errorRatePercent.toFixed(1)+'%)</td></tr>').join('')||'<tr><td colspan=\"6\" class=\"muted\">No matching HTTP traffic.</td></tr>';document.getElementById('alertRows').innerHTML=alerts.alerts.map(a=>'<tr><td><span class=\"pill error\">'+a.type+'</span></td><td>'+escapeHtml(a.scope)+'</td><td>'+a.value+'</td><td>'+a.threshold+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No active alerts.</td></tr>';"
                + "const max=Math.max(...timeline.points.map(p=>p.heapUsagePct),1);document.getElementById('timeline').innerHTML=timeline.points.map(p=>'<div class=\"bar\" title=\"'+p.heapUsagePct.toFixed(1)+'%\" style=\"height:'+Math.max(8,(p.heapUsagePct/max)*100)+'%\"></div>').join('');"
                + "document.getElementById('sqlTop').innerHTML=top.items.map(i=>'<tr><td><button type=\"button\" class=\"sql-click\">'+escapeHtml(i.sql)+'</button></td><td>'+i.count+'</td><td>'+i.avgMs.toFixed(1)+' ms</td><td>'+i.maxMs+' ms</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No SQL data has been captured yet.</td></tr>';"
                + "document.getElementById('slowRows').innerHTML=slow.items.map(i=>'<tr><td>'+new Date(i.timestamp).toLocaleTimeString()+'</td><td><span class=\"pill '+i.status.toLowerCase()+'\">'+i.status+'</span></td><td>'+i.durationMs+' ms</td><td><button type=\"button\" class=\"sql-click\">'+escapeHtml(i.sql)+'</button></td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No slow queries have been captured recently.</td></tr>';"
                + "document.getElementById('errorRows').innerHTML=errors.items.map(i=>'<tr><td>'+new Date(i.timestamp).toLocaleTimeString()+'</td><td>'+escapeHtml(i.uri)+'</td><td><span class=\"pill error\">'+i.status+'</span></td><td>'+escapeHtml(i.message)+'</td></tr>').join('')||'<tr><td colspan=\"4\" class=\"muted\">No exceptions or HTTP 5xx errors captured recently.</td></tr>';"
                + "renderSqlHeatmap(sqlTimeline.points.slice(-120));document.getElementById('collectorQueue').textContent=collector.queueSize+'/'+collector.capacity;document.getElementById('collectorDropped').textContent=collector.dropped;document.getElementById('collectorSent').textContent=collector.sent;"
                + "const trendPoints=trafficTimeline.points.slice(-30),maxTps=Math.max(...trendPoints.map(p=>p.tps),1);document.getElementById('trafficTrend').innerHTML=trendPoints.map(p=>'<span class=\"trend-item\" title=\"Active '+p.activeRequests+' · Error '+p.errorRatePercent.toFixed(2)+'%\"><b>'+p.tps+'</b><i style=\"height:'+Math.max(4,(p.tps/maxTps)*90)+'px\"></i></span>').join('')||'<span class=\"muted\">No WAS trend data yet.</span>';"
                + "document.getElementById('collectorRows').innerHTML=(collectorSummary.items||[]).slice(0,100).map(i=>'<tr><td>'+new Date(i.bucket).toLocaleString()+'</td><td>'+escapeHtml(i.service)+'</td><td>'+escapeHtml(i.type)+'</td><td>'+i.count+'</td><td>'+i.errors+'</td><td>'+i.maxDurationMs+' ms</td></tr>').join('')||'<tr><td colspan=\"6\" class=\"muted\">수집기 이력이 없습니다.</td></tr>';"
                + "}let previousInTps=null,previousComboTier=0,comboTimer=null;function updateTrafficPulse(inTps,outTps,timeline,active){const incoming=Math.max(0,Number(inTps)||0),outgoing=Math.max(0,Number(outTps)||0),load=Math.max(incoming,outgoing),speed=load>0?Math.max(.18,1/(1+Math.sqrt(load)/3)):2.8,inSpeed=incoming>0?Math.max(.18,1/(1+Math.sqrt(incoming)/3)):2.8,outSpeed=outgoing>0?Math.max(.18,1/(1+Math.sqrt(outgoing)/3)):2.8,stage=document.getElementById('flowStage'),loadBadge=document.getElementById('trafficLoad'),level=load===0?'idle':load>=300?'high':load>=100?'medium':'low';stage.style.setProperty('--flow-speed',speed.toFixed(2)+'s');stage.style.setProperty('--flow-in-speed',inSpeed.toFixed(2)+'s');stage.style.setProperty('--flow-out-speed',outSpeed.toFixed(2)+'s');document.querySelector('.flow-lane.in').classList.toggle('flow-idle',incoming<=0);document.querySelector('.flow-lane.out').classList.toggle('flow-idle',outgoing<=0);stage.classList.remove('idle','level-low','level-medium','level-high');stage.classList.add('level-'+level);loadBadge.className='load-badge'+(level==='medium'?' medium':level==='high'?' high':'');loadBadge.textContent=level==='high'?'트래픽 급증':level==='medium'?'혼잡':level==='low'?'활성':'IDLE';document.getElementById('flowInRate').textContent=incoming+' /s';document.getElementById('flowOutRate').textContent=outgoing+' /s';const comboTier=incoming>=110?Math.floor((incoming-100)/10):0,card=document.getElementById('tpsKpi'),badge=document.getElementById('trafficCombo');card.classList.remove('tps-low','tps-medium','tps-high','tps-idle');card.classList.add('tps-'+level);if(comboTier>previousComboTier&&comboTier>0){const combo=Math.min(5,comboTier-Math.max(0,previousComboTier));card.style.setProperty('--punch-scale',(1+Math.min(.22,combo*.045)).toFixed(2));card.classList.remove('punch');void card.offsetWidth;card.classList.add('punch');badge.textContent='COMBO ×'+combo;badge.classList.add('combo-hit');if(comboTimer)clearTimeout(comboTimer);comboTimer=setTimeout(()=>{card.classList.remove('punch');badge.classList.remove('combo-hit');badge.textContent=level==='idle'?'IDLE':'흐르는 중';},950);}else if(previousInTps===null){badge.textContent=level==='idle'?'IDLE':'흐르는 중';}previousComboTier=comboTier;previousInTps=incoming;}function renderSqlHeatmap(points){const plot=document.getElementById('sqlPoints'),yAxis=document.getElementById('sqlYAxis'),xAxis=document.getElementById('sqlXAxis'),pick=document.getElementById('sqlPick'),now=Date.now(),windowMs=300000,start=now-windowMs,visible=points.filter(p=>p.timestamp>=start&&p.timestamp<=now);if(!visible.length){plot.innerHTML='';yAxis.innerHTML='<span>—</span>';xAxis.innerHTML='<span>5분 전</span><span>2분 30초 전</span><span>지금</span>';pick.textContent='최근 5분 내 SQL 실행 데이터가 없습니다.';return;}const maxMs=Math.max(1,...visible.map(p=>p.durationMs));yAxis.innerHTML='<span>'+maxMs+' ms</span><span>'+Math.round(maxMs*.75)+' ms</span><span>'+Math.round(maxMs*.5)+' ms</span><span>'+Math.round(maxMs*.25)+' ms</span><span>0 ms</span>';xAxis.innerHTML='<span>5분 전</span><span>2분 30초 전</span><span>지금</span>';const existing=new Map(Array.from(plot.children).map(dot=>[dot.dataset.key,dot])),seen={};visible.forEach((p,i)=>{const base=p.timestamp+':'+p.durationMs+':'+p.color+':'+p.sql,ordinal=seen[base]=(seen[base]||0)+1,key=base+':'+ordinal;let dot=existing.get(key);if(dot){existing.delete(key);}else{dot=document.createElement('button');dot.type='button';dot.className='sql-point '+p.color;dot.dataset.key=key;plot.appendChild(dot);}dot.dataset.index=i;dot.setAttribute('aria-label',p.durationMs+' ms at '+new Date(p.timestamp).toLocaleTimeString());dot.title=p.durationMs+' ms · '+new Date(p.timestamp).toLocaleTimeString();dot.style.left=Math.max(0,Math.min(100,(p.timestamp-start)/windowMs*100))+'%';dot.style.bottom=Math.max(1,p.durationMs/maxMs*96)+'%';});existing.forEach(dot=>dot.remove());plot._heatmapPoints=visible;plot.onpointerover=plot.onfocusin=plot.onclick=function(e){const dot=e.target.closest('.sql-point');if(!dot)return;plot.querySelectorAll('.selected').forEach(n=>n.classList.remove('selected'));dot.classList.add('selected');const p=plot._heatmapPoints[Number(dot.dataset.index)];if(!p)return;pick.innerHTML='<strong>'+p.durationMs+' ms</strong><span>'+new Date(p.timestamp).toLocaleString()+'</span><code class=\"sql-pick-code sql-click\" tabindex=\"0\" role=\"button\"></code>';pick.querySelector('code').textContent=p.sql;};}function escapeHtml(v){return String(v||'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');}function formatSql(sql){const s=String(sql||'').trim(),clauses=['group by','order by','partition by','insert into','delete from','left outer join','right outer join','full outer join','inner join','left join','right join','full join','cross join','union all','union','intersect','except','select','from','where','having','limit','offset','fetch','returning','set','values','join','on'];let out='',i=0,depth=0,quote=0;while(i<s.length){const c=s.charCodeAt(i),n=s.charCodeAt(i+1);if(quote){out+=s[i++];if(c===(quote===91?93:quote)){if(s.charCodeAt(i)===c){out+=s[i++];}else quote=0;}else if(c===92&&i<s.length)out+=s[i++];continue;}if(c===39||c===34||c===96||c===91){quote=c;out+=s[i++];continue;}if(c===45&&n===45){while(i<s.length&&s.charCodeAt(i)!==10)out+=s[i++];continue;}if(c===47&&n===42){out+=s[i++]+s[i++];while(i<s.length){const x=s.charCodeAt(i);out+=s[i++];if(x===42&&s.charCodeAt(i)===47){out+=s[i++];break;}}continue;}if(/[A-Za-z_]/.test(s[i])){let matched='';for(const k of clauses){const re=new RegExp('^'+k.replace(' ','\\\\s+')+'\\\\b','i'),m=s.slice(i).match(re);if(m&&m[0].length>matched.length)matched=m[0];}if(matched){const prefix=out.replace(/[ \t]+$/,'');out=prefix+(prefix?'\\n':'')+'  '.repeat(depth)+matched;i+=matched.length;continue;}out+=s[i++];continue;}if(c===40){depth++;out+=s[i++];continue;}if(c===41){depth=Math.max(0,depth-1);out+=s[i++];continue;}if(c===44&&depth===0){out=out.replace(/[ \t]+$/,'')+',\\n  ';i++;continue;}if(/\s/.test(s[i])){if(out&&!/[ \\n]$/.test(out))out+=' ';i++;continue;}out+=s[i++];}return out.trim();}function showSqlModal(sql){const modal=document.getElementById('sqlModal');document.getElementById('sqlModalText').textContent=formatSql(sql);if(!modal.open)modal.showModal();}document.addEventListener('click',e=>{const trigger=e.target.closest('.sql-click');if(trigger)showSqlModal(trigger.textContent);});document.addEventListener('keydown',e=>{if((e.key==='Enter'||e.key===' ')&&e.target.matches('.sql-pick-code')){e.preventDefault();showSqlModal(e.target.textContent);}});document.getElementById('sqlModalClose').addEventListener('click',()=>document.getElementById('sqlModal').close());load();setInterval(load,2000);"
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
            offer("{\"type\":\"TRAFFIC\",\"service\":\"" + escapeJson(CONFIG.serviceName) + "\",\"timestamp\":" + snapshot.timestamp + ",\"tps\":" + snapshot.tps + ",\"responsesPerSecond\":" + snapshot.responsesPerSecond + ",\"activeRequests\":" + snapshot.activeRequests + ",\"errorRatePercent\":" + snapshot.errorRatePercent + "}");
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
            return normalized;
        }
    }

    private static final class TrafficMetrics {
        private static final int MAX_URI_STATS = 1000;
        private final AtomicLong totalHits = new AtomicLong();
        private final AtomicLong totalErrors = new AtomicLong();
        private final AtomicLong totalCompleted = new AtomicLong();
        private final AtomicLong activeRequests = new AtomicLong();
        private final AtomicLong lastHitsBase = new AtomicLong();
        private final AtomicLong lastErrorsBase = new AtomicLong();
        private final AtomicLong lastCompletedBase = new AtomicLong();
        private volatile long lastHitsRotateNanos = System.nanoTime();
        private volatile long hitsPerSecond;
        private volatile long responsesPerSecond;
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
            totalCompleted.incrementAndGet();
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
            long completed = totalCompleted.get();
            long completedDelta = completed - lastCompletedBase.getAndSet(completed);
            responsesPerSecond = elapsedNanos <= 0L ? completedDelta : Math.round(completedDelta * (1_000_000_000D / elapsedNanos));
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
                        .append("\"p95Ms\":").append(formatDouble(stat.p95Ms())).append(',')
                        .append("\"maxMs\":").append(TimeUnit.NANOSECONDS.toMillis(stat.maxNanos.get())).append(',')
                        .append("\"errors\":").append(stat.errors.get()).append(',')
                        .append("\"errorRatePercent\":").append(formatDouble(stat.errorRatePercent)).append('}');
            }
            items.append(']');
            return "{"
                    + "\"totalHits\":" + totalHits.get() + ","
                    + "\"hitsPerSecond\":" + hitsPerSecond + ","
                    + "\"tps\":" + hitsPerSecond + ","
                    + "\"responsesPerSecond\":" + responsesPerSecond + ","
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
            snapshot.responsesPerSecond = responsesPerSecond;
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
                        .append(",\"responsesPerSecond\":").append(point.responsesPerSecond)
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
                try {
                    Object webRequest = request.getClass().getMethod("getRequest").invoke(request);
                    Object uri = webRequest.getClass().getMethod("getURI").invoke(webRequest);
                    Object path = uri.getClass().getMethod("getPath").invoke(uri);
                    return truncate(path == null ? "unknown" : String.valueOf(path), 200);
                } catch (Throwable ignoredAgain) {
                    return "unknown";
                }
            }
        }

        private int responseStatus(Object response) {
            if (response == null) return 0;
            try {
                Method method = response.getClass().getMethod("getStatus");
                Object value = method.invoke(response);
                return value instanceof Number ? ((Number) value).intValue() : 0;
            } catch (Throwable ignored) {
                try {
                    Object status = response.getClass().getMethod("getStatusCode").invoke(response);
                    Object value = status.getClass().getMethod("value").invoke(status);
                    return value instanceof Number ? ((Number) value).intValue() : 0;
                } catch (Throwable ignoredAgain) {
                    return 0;
                }
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
        private final Deque<Long> durationSamples = new ConcurrentLinkedDeque<Long>();
        private final AtomicLong lastHitsBase = new AtomicLong();
        private final AtomicLong lastErrorsBase = new AtomicLong();
        private volatile long hitsPerSecond;
        private volatile double errorRatePercent;

        private TrafficStat(String uri) { this.uri = uri; }

        private void observe(long durationNanos, boolean error) {
            hits.incrementAndGet();
            totalNanos.addAndGet(durationNanos);
            updateMax(durationNanos);
            durationSamples.addLast(durationNanos);
            while (durationSamples.size() > 128) durationSamples.pollFirst();
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

        private double p95Ms() {
            List<Long> values = new ArrayList<Long>(durationSamples);
            if (values.isEmpty()) return 0D;
            Collections.sort(values);
            int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95D) - 1);
            return values.get(index) / 1_000_000D;
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
        private long responsesPerSecond;
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
