import com.fasterxml.jackson.databind.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * 固定热点与普通旧读版本的性能助手，独立JVM运行，不属于业务Spring Bean。
 * 入口：deploy/performance/run-current.py，指定本轮独立运行目录。
 * 作用：专用夹具、只绑定本机的HTTP控制/TCP故障代理、直接SQL/Redis观测和清理。
 * 会写测试数据，代理只影响测试应用连接；观测查询也有开销，应结合测试原理解释数据。
 */
public class CurrentPerfHarness {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final List<Long> shops = new CopyOnWriteArrayList<>(), vouchers = new CopyOnWriteArrayList<>();
    static final List<Map<String, String>> users = new ArrayList<>();
    static final Map<Long, Map<String, Object>> terminal = new ConcurrentHashMap<>();
    static final Map<Long, Long> commitSeen = new ConcurrentHashMap<>();
    static final AtomicLong edgeSuccess = new AtomicLong(), edgeFailure = new AtomicLong();
    static final AtomicBoolean edgeHealthy = new AtomicBoolean(true), observing = new AtomicBoolean(true);
    static volatile long activeVoucher = -1, activeStock, activeDbStock;
    static volatile Object syncResult;
    static volatile String syncState = "idle";
    static String dbPassword, redisPassword, redisHost, marker, stream, internalKey, group = "g1";
    static final int DATABASE = 14;
    static volatile String syncKind = "HOT";
    static final List<Long> hotShops = new ArrayList<>();
    static long baselineCatalogSize;
    static int redisPort;
    static Path run;
    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static RedisCommands<String, String> redis;
    static TcpGate mysqlGate, redisGate;
    static HttpServer server;
    static double clockOffset, clockUncertainty;

    /**
     * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
     */
    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of("src/main/resources/db/migration/V4__fixed_shop_cache_catalog.sql"))
                && !Files.readString(Path.of("src/main/java/com/hmdp/cache/ShopCacheCatalog.java")).contains("fingerprint"))
            throw new IllegalStateException("This tool requires the fixed HOT catalog implementation");
        run = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(run);
        if (Files.exists(run.resolve("manifest.json"))) throw new IllegalStateException("Use a new run directory");
        internalKey = Files.readString(Path.of(".local/cache-sync/internal-cache-key.txt")).trim();
        marker = "perf-" + run.getFileName() + "-";
        stream = "perf:" + run.getFileName() + ":orders";
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        dbPassword = match(yaml, "(?m)^    password: (.+)$");
        String ry = yaml.substring(yaml.indexOf("  redis:"), yaml.indexOf("  jackson:"));
        redisHost = match(ry, "(?m)^    host: (.+)$");
        redisPort = Integer.parseInt(match(ry, "(?m)^    port: (.+)$"));
        redisPassword = match(ry, "(?m)^    password: (.+)$");
        client = RedisClient.create(RedisURI.Builder.redis(redisHost, redisPort).withDatabase(DATABASE).withPassword(redisPassword.toCharArray()).build());
        connection = client.connect();
        redis = connection.sync();
        baselineCatalogSize = redis.dbsize();
        if (redis.exists("cache:shop:v3:catalog") > 0)
            throw new IllegalStateException("DB14 already has a fixed catalog; refusing to replace it");
        calibrate();
        mysqlGate = new TcpGate(13306, "127.0.0.1", 3306, "mysql");
        redisGate = new TcpGate(16379, redisHost, redisPort, "redis");
        mysqlGate.start();
        redisGate.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 19080), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", CurrentPerfHarness::handle);
        server.start();
        Thread.ofVirtual().name("order-observer").start(CurrentPerfHarness::observe);
        Files.writeString(run.resolve("ready.json"), JSON.writeValueAsString(Map.of("stream", stream, "redisDatabase", DATABASE, "marker", marker)));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            observing.set(false);
            server.stop(0);
            connection.close();
            client.shutdown();
        }));
        System.out.println("READY V4 helper :19080; isolated DB14 and marked SQL rows");
        new CountDownLatch(1).await();
    }

    static void handle(HttpExchange exchange) throws IOException {
        Object body;
        int status = 200;
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> q = query(exchange.getRequestURI());
            if (path.startsWith("/edge/")) {
                long id = Long.parseLong(path.substring(6));
                boolean ok = edgeHealthy.get();
                int result = 503;
                if (ok)
                    result = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18084/internal/cache/shop/" + id)).header("X-HMDP-Internal-Key", internalKey).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                if (result == 200) edgeSuccess.incrementAndGet();
                else edgeFailure.incrementAndGet();
                append("edge-events.jsonl", Map.of("time", Instant.now().toString(), "epochMillis", System.currentTimeMillis(), "shopId", id, "status", result));
                exchange.sendResponseHeaders(result, -1);
                exchange.close();
                return;
            }
            body = switch (path) {
                case "/health" ->
                        Map.of("status", "UP", "stream", stream, "edgeSuccess", edgeSuccess.get(), "edgeFailure", edgeFailure.get());
                case "/prepare" -> prepare();
                case "/environment" -> environment();
                case "/clock" -> {
                    calibrate();
                    yield Map.of("offsetHostMinusRedisMs", clockOffset, "halfMinimumRttMs", clockUncertainty);
                }
                case "/cache-stats" -> cacheStats();
                case "/burst" -> burst(Long.parseLong(q.get("id")), Integer.parseInt(q.getOrDefault("port", "18085")));
                case "/pause-observer" -> {
                    activeVoucher = -1;
                    yield Map.of("paused", true);
                }
                case "/voucher" ->
                        voucher(Integer.parseInt(q.getOrDefault("stock", "1000")), Integer.parseInt(q.getOrDefault("dbStock", q.getOrDefault("stock", "1000"))));
                case "/snapshot" -> snapshot(Long.parseLong(q.getOrDefault("id", Long.toString(activeVoucher))));
                case "/fault" -> fault(q.get("target"), Boolean.parseBoolean(q.get("healthy")));
                case "/clear" -> clear(Long.parseLong(q.get("id")), false);
                case "/logical" -> clear(Long.parseLong(q.get("id")), true);
                case "/digest" -> digest();
                case "/replay" -> replay(Long.parseLong(q.get("id")), Integer.parseInt(q.getOrDefault("count", "100")));
                case "/sync-start" -> {
                    syncKind = q.getOrDefault("kind", "HOT");
                    yield startSync(Integer.parseInt(q.getOrDefault("count", "30")));
                }
                case "/sync-result" -> Map.of("state", syncState, "result", syncResult == null ? Map.of() : syncResult);
                case "/update" -> updateShop(Long.parseLong(q.get("id")), q.getOrDefault("version", "recovery"));
                case "/read" -> readAll(Long.parseLong(q.get("id")));
                case "/cleanup" -> cleanup();
                default -> throw new IllegalArgumentException("Unknown control path");
            };
        } catch (Throwable e) {
            e.printStackTrace();
            status = 500;
            body = Map.of("error", e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage()));
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static Connection db() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=10000", "root", dbPassword);
    }

    static void calibrate() {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 10; i++) {
            long before = System.currentTimeMillis(), nano = System.nanoTime();
            var t = redis.time();
            double elapsed = (System.nanoTime() - nano) / 1e6;
            double remote = Long.parseLong(t.get(0)) * 1000d + Long.parseLong(t.get(1)) / 1000d;
            if (elapsed < best) {
                best = elapsed;
                clockOffset = before + elapsed / 2 - remote;
                clockUncertainty = elapsed / 2;
            }
        }
    }

    static Object environment() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redisInfo", redis.info());
        result.put("redisDbSizeBefore", redis.dbsize());
        result.put("redisClockOffsetHostMinusRedisMs", clockOffset);
        result.put("clockMinimumRttHalfMs", clockUncertainty);
        Map<String, String> values = new LinkedHashMap<>();
        try (var c = db(); var s = c.createStatement(); var rs = s.executeQuery("SELECT VERSION(),@@global.binlog_format,@@global.binlog_row_image,@@global.innodb_flush_log_at_trx_commit,@@global.sync_binlog,@@global.max_connections,@@global.performance_schema")) {
            rs.next();
            String[] names = {"version", "binlogFormat", "binlogRowImage", "innodbFlushLogAtTrxCommit", "syncBinlog", "maxConnections", "performanceSchema"};
            for (int i = 0; i < names.length; i++) values.put(names[i], rs.getString(i + 1));
        }
        try (var c = db(); var s = c.createStatement(); var rs = s.executeQuery("SHOW INDEX FROM tb_voucher_order WHERE Key_name='uk_user_voucher'")) {
            int n = 0;
            while (rs.next()) n++;
            if (n != 2) throw new IllegalStateException("Required unique user/voucher index missing");
        }
        result.put("mysql", values);
        Files.writeString(run.resolve("dependencies.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return result;
    }

    static synchronized Object prepare() throws Exception {
        if (!shops.isEmpty()) throw new IllegalStateException("Fixtures already prepared");
        List<Long> hot = new ArrayList<>(), other = new ArrayList<>(), cold = new ArrayList<>();
        try (var c = db()) {
            for (int i = 0; i < 354; i++) {
                try (var s = c.prepareStatement("INSERT INTO tb_shop(name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,create_time,update_time) SELECT ?,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,NOW(),NOW() FROM tb_shop WHERE name NOT LIKE 'perf-%' ORDER BY id LIMIT 1", Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1, marker + "shop-" + i);
                    if (s.executeUpdate() != 1) throw new IllegalStateException("No source shop");
                    try (var keys = s.getGeneratedKeys()) {
                        keys.next();
                        long id = keys.getLong(1);
                        shops.add(id);
                        if (i < 4) hot.add(id);
                        else if (i < 54) other.add(id);
                        else cold.add(id);
                    }
                }
                if (i % 25 == 0) saveManifest();
            }
        }
        hotShops.addAll(hot);
        for (int i = 0; i < 4000; i++)
            users.add(Map.of("id", Long.toString(9_100_000_000_000L + i), "token", marker + "user-" + i));
        // Pipeline setup only, not business requests. No credentials or real user tokens are written.
        try (var pipeline = client.connect()) {
            pipeline.setAutoFlushCommands(false);
            var commands = pipeline.async();
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (var u : users) {
                futures.add(commands.hset("login:token:" + u.get("token"), Map.of("id", u.get("id"), "nickName", "Performance fixture")).toCompletableFuture());
                futures.add(commands.expire("login:token:" + u.get("token"), 10800).toCompletableFuture());
            }
            pipeline.flushCommands();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
        }
        saveManifest();
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("hotShops", hot);
        fixture.put("otherShops", other);
        fixture.put("coldShops", cold);
        fixture.put("users", users);
        fixture.put("stream", stream);
        fixture.put("marker", marker);
        Files.writeString(run.resolve("fixture.json"), JSON.writeValueAsString(fixture));
        return Map.of("shops", shops.size(), "users", users.size(), "hotShops", hot, "stream", stream);
    }

    static synchronized Object voucher(int stock, int dbStock) throws Exception {
        long id;
        try (var c = db(); var s = c.prepareStatement("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type) VALUES (?,?,1,1,1)", Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1, shops.getFirst());
            s.setString(2, marker + "voucher-" + vouchers.size());
            s.executeUpdate();
            try (var k = s.getGeneratedKeys()) {
                k.next();
                id = k.getLong(1);
            }
            try (var v = c.prepareStatement("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,?,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 3 HOUR)")) {
                v.setLong(1, id);
                v.setInt(2, dbStock);
                v.executeUpdate();
            }
        }
        vouchers.add(id);
        saveManifest();
        long now = System.currentTimeMillis() - (long) clockOffset;
        redis.mset(Map.of("seckill:stock:" + id, Integer.toString(stock), "seckill:begin:" + id, Long.toString(now - 3600000), "seckill:end:" + id, Long.toString(now + 10800000)));
        activeStock = stock;
        activeDbStock = dbStock;
        activeVoucher = id;
        return Map.of("id", Long.toString(id), "redisInitialStock", stock, "mysqlInitialStock", dbStock);
    }

    static void observe() {
        while (observing.get()) {
            try (var c = db()) {
                while (observing.get()) {
                    long id = activeVoucher;
                    List<Map<String, Object>> fresh = new ArrayList<>();
                    if (id > 0)
                        try (var s = c.prepareStatement("SELECT order_id,user_id,state,compensated FROM tb_seckill_order_task WHERE voucher_id=?")) {
                            s.setLong(1, id);
                            try (var rows = s.executeQuery()) {
                                while (rows.next()) {
                                    long order = rows.getLong(1);
                                    String state = rows.getString(3);
                                    boolean finished = state.equals("SUCCEEDED") || (state.equals("FAILED") && rows.getBoolean(4));
                                    if (finished && !terminal.containsKey(order))
                                        fresh.add(new LinkedHashMap<>(Map.of("orderId", Long.toString(order), "voucherId", Long.toString(id), "userId", rows.getString(2), "state", state)));
                                }
                            }
                        }
                    // 先记录数据库结果集可见时间，再批量取受理时间；不把逐条Redis网络往返计入数据库提交可见延迟。
                    long visibleAt = System.currentTimeMillis();
                    List<CompletableFuture<String>> accepted = new ArrayList<>();
                    for (var item : fresh)
                        accepted.add(connection.async().hget("seckill:result:" + item.get("orderId"), "acceptedAt").toCompletableFuture());
                    CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                    for (int i = 0; i < fresh.size(); i++) {
                        String at = accepted.get(i).get();
                        if (at == null) continue;
                        var item = fresh.get(i);
                        double latency = visibleAt - (Long.parseLong(at) + clockOffset);
                        item.put("acceptedRedisEpochMs", at);
                        item.put("observedHostEpochMs", visibleAt);
                        item.put("latencyMs", latency);
                        item.put("clockOffsetHostMinusRedisMs", clockOffset);
                        long order = Long.parseLong(item.get("orderId").toString());
                        terminal.put(order, item);
                        if (item.get("state").equals("SUCCEEDED")) commitSeen.put(order, visibleAt);
                        append("order-observations.jsonl", item);
                    }
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                try {
                    append("observer-errors.jsonl", Map.of("time", Instant.now().toString(), "error", String.valueOf(e)));
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
            }
        }
    }

    static Object snapshot(long id) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voucherId", Long.toString(id));
        result.put("time", Instant.now().toString());
        if (id > 0) try (var c = db()) {
            try (var s = c.prepareStatement("SELECT COUNT(*),COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id=?")) {
                s.setLong(1, id);
                try (var r = s.executeQuery()) {
                    r.next();
                    result.put("orders", r.getLong(1));
                    result.put("distinctUsers", r.getLong(2));
                }
            }
            try (var s = c.prepareStatement("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?")) {
                s.setLong(1, id);
                try (var r = s.executeQuery()) {
                    r.next();
                    result.put("mysqlStock", r.getInt(1));
                }
            }
            List<Map<String, Object>> states = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT state,compensated,COUNT(*) FROM tb_seckill_order_task WHERE voucher_id=? GROUP BY state,compensated")) {
                s.setLong(1, id);
                try (var r = s.executeQuery()) {
                    while (r.next())
                        states.add(Map.of("state", r.getString(1), "compensated", r.getBoolean(2), "count", r.getLong(3)));
                }
            }
            result.put("tasks", states);
            result.put("redisStock", redis.get("seckill:stock:" + id));
            result.put("purchasers", redis.scard("seckill:order:" + id));
            result.put("reservations", redis.hlen("seckill:reservation:" + id));
            List<Double> latencies = terminal.values().stream().filter(x -> x.get("voucherId").equals(Long.toString(id))).map(x -> ((Number) x.get("latencyMs")).doubleValue()).sorted().toList();
            result.put("terminalObservation", stats(latencies));
            var items = terminal.values().stream().filter(x -> x.get("voucherId").equals(Long.toString(id))).toList();
            if (!items.isEmpty()) {
                long first = items.stream().mapToLong(x -> Long.parseLong(x.get("acceptedRedisEpochMs").toString())).min().orElse(0), last = items.stream().mapToLong(x -> ((Number) x.get("observedHostEpochMs")).longValue()).max().orElse(0);
                result.put("acceptedToLastVisibleMs", last - (first + clockOffset));
            }
        }
        result.put("streamLength", redis.exists(stream) > 0 ? redis.xlen(stream) : 0);
        result.put("dlqLength", redis.exists(stream + ":dlq") > 0 ? redis.xlen(stream + ":dlq") : 0);
        result.put("archiveLength", redis.exists(stream + ":archive") > 0 ? redis.xlen(stream + ":archive") : 0);
        try {
            result.put("pending", redis.xpending(stream, group).getCount());
            String info = redis.eval("return cjson.encode(redis.call('XINFO','GROUPS',KEYS[1]))", ScriptOutputType.VALUE, new String[]{stream});
            result.put("groups", JSON.readTree(info));
            var last = redis.xrevrange(stream, Range.unbounded(), Limit.from(1));
            result.put("lastStreamId", last.isEmpty() ? "0-0" : last.getFirst().getId());
        } catch (Exception e) {
            result.put("streamInspectionError", e.getClass().getSimpleName());
        }
        result.put("edgeSuccess", edgeSuccess.get());
        result.put("edgeFailure", edgeFailure.get());
        return result;
    }

    static Object clear(long id, boolean logical) throws Exception {
        if (!shops.contains(id)) throw new IllegalArgumentException("Only owned shop may be modified");
        if (logical) {
            if (!hotShops.contains(id)) throw new IllegalArgumentException("Logical expiry only applies to owned HOT");
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(redis.get("cache:shop:v3:hot:" + id));
            value.put("logicalExpireAtEpochMillis", System.currentTimeMillis() - 30000);
            redis.set("cache:shop:v3:hot:" + id, JSON.writeValueAsString(value));
        } else {
            // 普通商铺没有上层副本，可以只清自有键构造真实冷启动；HOT不能用普通通知假装彻底清空。
            if (hotShops.contains(id)) throw new IllegalArgumentException("Cold reset must use an owned NORMAL shop");
            redis.del("cache:shop:v3:meta:" + id, "cache:shop:v3:normal:" + id);
        }
        return Map.of("shopId", id, "logicalExpired", logical);
    }

    static JsonNode authenticated(String url) throws Exception {
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("X-HMDP-Internal-Key", internalKey)
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Internal diagnostic HTTP " + r.statusCode());
        return JSON.readTree(r.body());
    }

    static Object cacheStats() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int port : new int[]{18085, 18086})
            result.put(Integer.toString(port), authenticated("http://127.0.0.1:" + port + "/internal/shop-cache/stats"));
        result.put("18084", authenticated("http://127.0.0.1:18084/internal/cache/stats"));
        List<Object> ttls = new ArrayList<>();
        for (long id : shops.subList(0, Math.min(8, shops.size())))
            ttls.add(Map.of("id", id, "hotTtl", redis.ttl("cache:shop:v3:hot:" + id), "normalTtl", redis.ttl("cache:shop:v3:normal:" + id)));
        result.put("redisTtls", ttls);
        return result;
    }

    static Object burst(long id, int port) throws Exception {
        if (!shops.contains(id) || hotShops.contains(id) || !List.of(18084, 18085).contains(port))
            throw new IllegalArgumentException("Owned NORMAL and test port required");
        long before = authenticated("http://127.0.0.1:18085/internal/shop-cache/stats").path("sourceDatabaseLoads").asLong();
        clear(id, false);
        // 一百个调用者经屏障同时出发，批次耗时和逐请求延迟分开记录。
        CyclicBarrier barrier = new CyclicBarrier(100);
        List<Future<Map<String, Object>>> tasks = new ArrayList<>();
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++)
                tasks.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    long t = System.nanoTime();
                    String path = port == 18084 ? "/api/shop/" : "/shop/";
                    var r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + id)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    JsonNode b = JSON.readTree(r.body());
                    return Map.of("ok", r.statusCode() == 200 && b.path("success").asBoolean() && b.path("data").path("id").asLong() == id, "latencyMs", (System.nanoTime() - t) / 1e6);
                }));
            List<Double> times = new ArrayList<>();
            long ok = 0;
            for (var task : tasks) {
                var r = task.get(15, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(r.get("ok"))) ok++;
                times.add(((Number) r.get("latencyMs")).doubleValue());
            }
            long after = authenticated("http://127.0.0.1:18085/internal/shop-cache/stats").path("sourceDatabaseLoads").asLong();
            return Map.of("requests", 100, "successful", ok, "batchMs", (System.nanoTime() - start) / 1e6, "latency", stats(times.stream().sorted().toList()), "businessJoinLoads", after - before);
        }
    }

    static Object replay(long id, int count) throws Exception {
        List<Map<String, String>> orders = new ArrayList<>();
        try (var c = db(); var s = c.prepareStatement("SELECT id,user_id FROM tb_voucher_order WHERE voucher_id=? ORDER BY id LIMIT 20")) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
                while (r.next())
                    orders.add(Map.of("id", r.getString(1), "userId", r.getString(2), "voucherId", Long.toString(id)));
            }
        }
        if (orders.isEmpty()) throw new IllegalStateException("No owned successful orders to replay");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) redis.xadd(stream, orders.get(i % orders.size()));
        return Map.of("messages", count, "enqueueMs", (System.nanoTime() - start) / 1e6);
    }

    static Object digest() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = db(); var s = c.createStatement(); var r = s.executeQuery("SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='hmdp' AND DIGEST_TEXT LIKE 'SELECT%tb_shop%'")) {
            while (r.next())
                rows.add(Map.of("digest", r.getString(1), "count", r.getLong(2), "totalWaitPs", r.getLong(3)));
        }
        return rows;
    }

    static Object fault(String target, boolean healthy) throws Exception {
        switch (target) {
            case "mysql" -> mysqlGate.setHealthy(healthy);
            case "redis" -> redisGate.setHealthy(healthy);
            case "edge" -> edgeHealthy.set(healthy);
            default -> throw new IllegalArgumentException("Unknown fault target");
        }
        Map<String, Object> event = Map.of("time", Instant.now().toString(), "epochMillis", System.currentTimeMillis(), "target", target, "healthy", healthy);
        append("fault-events.jsonl", event);
        return event;
    }

    static Object updateShop(long id, String version) throws Exception {
        if (!shops.contains(id)) throw new IllegalArgumentException("Not owned shop");
        String name = marker + version;
        long begin = System.currentTimeMillis();
        try (var c = db(); var s = c.prepareStatement("UPDATE tb_shop SET name=?,update_time=NOW() WHERE id=? AND name LIKE ?")) {
            s.setString(1, name);
            s.setLong(2, id);
            s.setString(3, marker + "%");
            if (s.executeUpdate() != 1) throw new IllegalStateException("Owned update failed");
        }
        long committed = System.currentTimeMillis();
        Map<String, Object> event = Map.of("shopId", id, "name", name, "startedHostEpochMs", begin, "commitReturnHostEpochMs", committed);
        append("shop-updates.jsonl", event);
        return event;
    }

    static Object readAll(long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int port : new int[]{18084, 18085, 18086}) {
            String uri = "http://127.0.0.1:" + port + (port == 18084 ? "/api/shop/" : "/shop/") + id;
            long start = System.nanoTime();
            try {
                var response = HTTP.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                JsonNode data = JSON.readTree(response.body());
                result.put(Integer.toString(port), Map.of("status", response.statusCode(), "success", data.path("success").asBoolean(), "name", data.path("data").path("name").asText(), "latencyMs", (System.nanoTime() - start) / 1e6));
            } catch (Exception e) {
                result.put(Integer.toString(port), Map.of("error", e.getClass().getSimpleName(), "latencyMs", (System.nanoTime() - start) / 1e6));
            }
        }
        return result;
    }

    static synchronized Object startSync(int count) {
        if (syncState.equals("running")) throw new IllegalStateException("Sync sampler already running");
        syncState = "running";
        syncResult = null;
        Thread.ofVirtual().start(() -> {
            List<Map<String, Object>> samples = new ArrayList<>();
            try {
                long id = syncKind.equals("HOT") ? shops.getFirst() : shops.get(4);
                for (int i = 0; i < count; i++) {
                    readAll(id);
                    long callbacksBefore = edgeSuccess.get();
                    var update = (Map<String, Object>) updateShop(id, "cdc-" + UUID.randomUUID());
                    String name = update.get("name").toString();
                    long committed = ((Number) update.get("commitReturnHostEpochMs")).longValue(), deadline = committed + 15000;
                    boolean fresh = false;
                    Map<String, Object> reads = Map.of();
                    do {
                        reads = (Map<String, Object>) readAll(id);
                        fresh = reads.values().stream().allMatch(x -> name.equals(((Map<?, ?>) x).get("name")));
                        if (fresh) break;
                        Thread.sleep(20);
                    } while (System.currentTimeMillis() < deadline);
                    Map<String, Object> sample = new LinkedHashMap<>(update);
                    sample.put("sample", i);
                    sample.put("freshAll", fresh);
                    sample.put("elapsedMs", System.currentTimeMillis() - committed);
                    sample.put("edgeCallbacks", edgeSuccess.get() - callbacksBefore);
                    sample.put("lastReads", reads);
                    samples.add(sample);
                    append("cdc-samples.jsonl", sample);
                    syncResult = Map.of("completed", samples.size(), "requested", count);
                    Thread.sleep(100);
                }
                syncResult = Map.of("samples", samples, "count", samples.size(), "successful", samples.stream().filter(x -> Boolean.TRUE.equals(x.get("freshAll"))).count(), "delay", stats(samples.stream().map(x -> ((Number) x.get("elapsedMs")).doubleValue()).sorted().toList()));
                syncState = "complete";
            } catch (Throwable e) {
                syncResult = Map.of("error", String.valueOf(e), "samples", samples);
                syncState = "failed";
            }
        });
        return Map.of("state", syncState, "requested", count);
    }

    static Object cleanup() throws Exception {
        activeVoucher = -1;
        observing.set(false);
        mysqlGate.setHealthy(true);
        redisGate.setHealthy(true);
        edgeHealthy.set(true);
        Thread.sleep(300);
        long deletedOrders = 0, deletedTasks = 0;
        Set<String> orderIds = new HashSet<>();
        if (redis.exists(stream) > 0) for (var m : redis.xrange(stream, Range.unbounded())) {
            String id = m.getBody().get("id");
            if (id != null) orderIds.add(id);
        }
        try (var c = db()) {
            for (long id : vouchers) {
                try (var verify = c.prepareStatement("SELECT title FROM tb_voucher WHERE id=?")) {
                    verify.setLong(1, id);
                    try (var r = verify.executeQuery()) {
                        if (r.next() && !r.getString(1).startsWith(marker))
                            throw new IllegalStateException("Cleanup ownership mismatch");
                    }
                }
                for (String table : List.of("tb_voucher_order", "tb_seckill_order_task", "tb_seckill_voucher")) {
                    try (var s = c.prepareStatement("DELETE FROM " + table + " WHERE voucher_id=?")) {
                        s.setLong(1, id);
                        int n = s.executeUpdate();
                        if (table.equals("tb_voucher_order")) deletedOrders += n;
                        if (table.equals("tb_seckill_order_task")) deletedTasks += n;
                    }
                }
                try (var s = c.prepareStatement("DELETE FROM tb_voucher WHERE id=? AND title LIKE ?")) {
                    s.setLong(1, id);
                    s.setString(2, marker + "%");
                    s.executeUpdate();
                }
                redis.del("seckill:stock:" + id, "seckill:begin:" + id, "seckill:end:" + id, "seckill:order:" + id, "seckill:reservation:" + id, "seckill:compensated:" + id);
            }
            for (long id : shops) {
                try (var s = c.prepareStatement("DELETE FROM tb_shop WHERE id=? AND name LIKE ?")) {
                    s.setLong(1, id);
                    s.setString(2, marker + "%");
                    s.executeUpdate();
                }
                redis.del("cache:shop:v3:meta:" + id, "cache:shop:v3:hot:" + id, "cache:shop:v3:normal:" + id, "lock:shop:rebuild:v3:" + id);
            }
        }
        for (String id : orderIds) redis.del("seckill:result:" + id);
        for (var u : users) redis.del("login:token:" + u.get("token"));
        redis.del(stream, stream + ":dlq", stream + ":archive");
        // 编排先停止本轮两个业务实例，才清除此轮创建的固定名单绑定；不清空整个逻辑库。
        redis.del("cache:shop:v3:catalog");
        Map<String, Object> result = Map.of("cleaned", true, "shops", shops.size(), "vouchers", vouchers.size(), "orders", deletedOrders, "tasks", deletedTasks, "redisDbSizeBefore", baselineCatalogSize, "redisDbSizeAfter", redis.dbsize(), "idCounterPreserved", true);
        Files.writeString(run.resolve("cleanup.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return result;
    }

    static Map<String, Object> stats(List<Double> values) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("n", values.size());
        if (!values.isEmpty()) {
            r.put("minMs", values.getFirst());
            r.put("p50Ms", percentile(values, .5));
            r.put("p95Ms", percentile(values, .95));
            r.put("p99Ms", percentile(values, .99));
            r.put("maxMs", values.getLast());
            r.put("meanMs", values.stream().mapToDouble(x -> x).average().orElse(0));
        }
        return r;
    }

    static double percentile(List<Double> v, double p) {
        double x = (v.size() - 1) * p;
        int lo = (int) x, hi = Math.min(lo + 1, v.size() - 1);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (x - lo);
    }

    static synchronized void saveManifest() throws IOException {
        Files.writeString(run.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("marker", marker, "shops", shops, "vouchers", vouchers, "stream", stream, "users", users)));
    }

    static synchronized void append(String name, Object value) throws IOException {
        Files.writeString(run.resolve(name), JSON.writeValueAsString(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static JsonNode get(String url) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> r = new HashMap<>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            r.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return r;
    }

    static String match(String text, String regex) {
        var m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new IllegalArgumentException("Missing configured property");
        return m.group(1).trim();
    }

    static class TcpGate {
        final int port, targetPort;
        final String targetHost, name;
        final AtomicBoolean healthy = new AtomicBoolean(true);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        TcpGate(int port, String targetHost, int targetPort, String name) {
            this.port = port;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.name = name;
        }

        void start() throws IOException {
            var listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", port));
            Thread.ofVirtual().start(() -> {
                while (true) try {
                    var incoming = listener.accept();
                    Thread.ofVirtual().start(() -> bridge(incoming));
                } catch (IOException e) {
                    break;
                }
            });
        }

        void setHealthy(boolean flag) {
            healthy.set(flag);
            if (!flag) for (Socket s : sockets)
                try {
                    s.close();
                } catch (IOException ignored) {
                }
        }

        void bridge(Socket in) {
            Socket out = null;
            try {
                if (!healthy.get()) {
                    in.close();
                    return;
                }
                out = new Socket();
                out.connect(new InetSocketAddress(targetHost, targetPort), 2000);
                in.setTcpNoDelay(true);
                out.setTcpNoDelay(true);
                sockets.add(in);
                sockets.add(out);
                Socket destination = out;
                Thread.ofVirtual().start(() -> copy(in, destination));
                copy(out, in);
            } catch (Exception ignored) {
            } finally {
                close(in);
                if (out != null) close(out);
            }
        }

        void copy(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                close(from);
                close(to);
            }
        }

        void close(Socket s) {
            sockets.remove(s);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
