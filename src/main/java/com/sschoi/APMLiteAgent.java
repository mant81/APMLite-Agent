package com.sschoi;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class APMLiteAgent {

    private static boolean USE_DB = true;
    private static String DB_URL = "jdbc:mariadb://localhost:3306/apm_lite";
    private static String DB_USER = "root";
    private static String DB_PASSWORD = "admin";

    private static long CHECK_INTERVAL_MS = 2000;
    private static long SLOW_QUERY_THRESHOLD_MS = 100;

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static AtomicLong queryCount = new AtomicLong(0);

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[APMLiteAgent] Agent started");

        parseArgs(agentArgs);
        startJVMMonitor();
        startTPSMonitor();
        hookJDBC(inst);
    }

    private static void parseArgs(String agentArgs) {
        if (agentArgs != null) {
            String[] args = agentArgs.split(";");
            for (String arg : args) {
                String[] kv = arg.split("=");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    switch (key) {
                        case "interval": CHECK_INTERVAL_MS = Long.parseLong(value); break;
                        case "slow_query": SLOW_QUERY_THRESHOLD_MS = Long.parseLong(value); break;
                        case "db_url": DB_URL = value; break;
                        case "db_user": DB_USER = value; break;
                        case "db_pass": DB_PASSWORD = value; break;
                        case "use_db": USE_DB = Boolean.parseBoolean(value); break;
                    }
                }
            }
        }
    }

    private static void startJVMMonitor() {
        new Thread(() -> {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            while (true) {
                try {
                    MemoryUsage heap = memoryBean.getHeapMemoryUsage();
                    double usedMB = heap.getUsed() / (1024.0*1024);
                    double maxMB = heap.getMax() / (1024.0*1024);
                    double usagePercent = (usedMB / maxMB) * 100;

                    logToDB("HEAP", "UsedHeap_MB", usedMB, null, 0);
                    logToDB("HEAP", "MaxHeap_MB", maxMB, null, 0);
                    logToDB("HEAP", "UsagePercent", usagePercent, null, 0);

                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }).start();
    }

    private static void startTPSMonitor() {
        new Thread(() -> {
            long prevCount = 0;
            while (true) {
                try {
                    Thread.sleep(1000); // 1초 단위
                    long currentCount = queryCount.get();
                    long tps = currentCount - prevCount;
                    prevCount = currentCount;

                    logToDB("SQL", "TPS", tps, null, 0);
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }).start();
    }

    private static void hookJDBC(Instrumentation inst) {
        new AgentBuilder.Default()
                .type(ElementMatchers.nameContainsIgnoreCase("Connection"))
                .transform((builder, typeDescription, classLoader, module) ->
                        builder.method(ElementMatchers.named("prepareStatement"))
                                .intercept(Advice.to(PrepareStatementAdvice.class))
                ).installOn(inst);
    }

    public static class PrepareStatementAdvice {

        @Advice.OnMethodExit
        static void onExit(@Advice.Return(readOnly = false) PreparedStatement stmt,
                           @Advice.Argument(0) String sql) {
            if (stmt == null) return;
            // public static 메서드 호출
            stmt = PrepareStatementAdvice.createProxy(stmt, sql);
        }

        public static PreparedStatement createProxy(final PreparedStatement stmt, final String sql) {
            final ConcurrentHashMap<Integer, Object> params = new ConcurrentHashMap<>();

            return (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                    stmt.getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class}, // HikariCP 호환
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("set") && args.length >= 2 && args[0] instanceof Integer) {
                            params.put((Integer) args[0], args[1]);
                        }

                        long start = System.currentTimeMillis();
                        Object result;
                        try {
                            result = method.invoke(stmt, args);
                        } catch (Throwable t) {
                            throw t.getCause() != null ? t.getCause() : t;
                        }
                        long duration = System.currentTimeMillis() - start;

                        if (method.getName().startsWith("execute")) {
                            queryCount.incrementAndGet();
                            if (duration >= SLOW_QUERY_THRESHOLD_MS) {
                                String sqlWithParams = fillParams(sql, params);
                                logToDB("SQL", "SlowQuery", 0, sqlWithParams, duration);
                            }
                        }
                        return result;
                    }
            );
        }

        private static String fillParams(String sql, ConcurrentHashMap<Integer, Object> params) {
            String filled = sql;
            for (Integer idx : params.keySet()) {
                Object val = params.get(idx);
                String strVal = val instanceof String ? "'" + val + "'" : String.valueOf(val);
                filled = filled.replaceFirst("\\?", strVal);
            }
            return filled;
        }
    }


    private static void logToDB(String type, String name, double value, String sql, long execTime) {
        if (!USE_DB) return;

        executor.submit(() -> {
            try {
                Class.forName("org.mariadb.jdbc.Driver");
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sqlInsert = "INSERT INTO apm_metrics " +
                            "(timestamp, metric_type, metric_name, metric_value, sql_query, exec_time_ms) " +
                            "VALUES (NOW(), ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
                        ps.setString(1, type);
                        ps.setString(2, name);
                        ps.setDouble(3, value);
                        ps.setString(4, sql);
                        ps.setLong(5, execTime);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
