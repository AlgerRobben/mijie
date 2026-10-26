import com.fasterxml.jackson.databind.*;
import com.hmdp.cache.*;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.*;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/**
 * JDK21独立验收工具，不进入业务JAR。仅操作UUID标记的商铺；删除版本记录保留以保证版本单调。
 * 入口verify-cache-sync.ps1，证据写.local/verification/fixed-cache，旧版实测结果不覆盖。
 * cdc验证真实MySQL/Canal/MQ/两JVM/OpenResty；retry单独验证通知组件与Broker重投/DLQ。
 */
public class CacheSyncE2E {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final Path ROOT = Path.of("").toAbsolutePath(), OUT = ROOT.resolve(".local/verification/fixed-cache");
    static final List<Map<String, Object>> CHECKS = new ArrayList<>();
    static String dbPassword, redisPassword, redisHost, key;
    static int redisPort;
    static String marker = "cache-fixed-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static RedisCommands<String, String> redis;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        String yaml = Files.readString(ROOT.resolve("src/main/resources/application.yaml"));
        dbPassword = match(yaml, "(?m)^    password: (.+)$");
        String ry = yaml.substring(yaml.indexOf("  redis:"), yaml.indexOf("  jackson:"));
        redisPassword = match(ry, "(?m)^    password: (.+)$");
        redisHost = match(ry, "(?m)^    host: (.+)$");
        redisPort = Integer.parseInt(match(ry, "(?m)^    port: (.+)$"));
        key = Files.readString(ROOT.resolve(".local/cache-sync/internal-cache-key.txt")).trim();
        String mode = args.length == 0 ? "cdc" : args[0];
        boolean passed = false;
        var client = RedisClient.create(RedisURI.Builder.redis(redisHost, redisPort).withPassword(redisPassword.toCharArray()).withDatabase(15).build());
        try (var connection = client.connect()) {
            redis = connection.sync();
            switch (mode) {
                case "prepare" -> prepare();
                case "cleanup" -> cleanup();
                case "retry" -> retry();
                case "cdc" -> cdc();
                default -> throw new IllegalArgumentException("Unknown mode");
            }
            passed = true;
        } finally {
            client.shutdown();
            Files.writeString(OUT.resolve(mode + "-result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("time", Instant.now().toString(), "passed", passed, "checks", CHECKS)));
        }
        System.out.println("PASS " + mode + " checks=" + CHECKS.size());
    }

    static Connection db() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", dbPassword);
    }

    static long create(Connection c) throws Exception {
        try (var s = c.prepareStatement("INSERT INTO tb_shop(name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,create_time,update_time) SELECT ?,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,NOW(6),NOW(6) FROM tb_shop ORDER BY id LIMIT 1", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, marker);
            require(s.executeUpdate() == 1, "insert fixture");
            try (var r = s.getGeneratedKeys()) {
                require(r.next(), "generated ID");
                return r.getLong(1);
            }
        }
    }

    static JsonNode response(int port, String path, boolean secret, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (secret) builder.header(InternalCacheAccess.HEADER, key);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var r = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        require(r.statusCode() == 200, "HTTP " + port + path + " returned " + r.statusCode() + " " + r.body());
        return JSON.readTree(r.body());
    }

    static JsonNode stats(int port) throws Exception {
        return response(port, port == 8080 ? "/internal/cache/stats" : "/internal/shop-cache/stats", true, null);
    }

    static JsonNode shop(int port, long id) throws Exception {
        return response(port, (port == 8080 ? "/api/shop/" : "/shop/") + id, false, null);
    }

    static String name(int port, long id) {
        try {
            return shop(port, id).path("data").path("name").asText();
        } catch (Exception e) {
            return "";
        }
    }

    static long loads(int port) throws Exception {
        return stats(port).path("sourceDatabaseLoads").asLong();
    }

    static boolean resident(int port, long id) throws Exception {
        var s = stats(port);
        var ids = port == 8080 ? s.path("ids") : s.path("hot").path("ids");
        for (var value : ids) if (value.asText().equals(Long.toString(id))) return true;
        return false;
    }

    static JsonNode snapshot(long id) throws Exception {
        return JSON.readTree(redis.get("cache:shop:v3:hot:" + id));
    }

    static JsonNode fixture() throws Exception {
        return JSON.readTree(Files.readString(OUT.resolve("fixture.json")));
    }

    static void clearTestCache() {
        // 仅限测试15号库的项目v3命名空间，绝不FLUSHDB或删除订单/GEO数据。
        for (String pattern : new String[]{"cache:shop:v3:*", "lock:shop:rebuild:v3:*"}) {
            String cursor = "0";
            do {
                var scan = redis.scan(ScanCursor.of(cursor), ScanArgs.Builder.matches(pattern).limit(100));
                if (!scan.getKeys().isEmpty()) redis.del(scan.getKeys().toArray(String[]::new));
                cursor = scan.getCursor();
            } while (!cursor.equals("0"));
        }
    }

    static void prepare() throws Exception {
        try (var c = db()) {
            try (var q = c.createStatement(); var result = q.executeQuery("SELECT COUNT(*) FROM tb_shop WHERE name LIKE 'cache-fixed-e2e-%'")) {
                result.next();
                require(result.getLong(1) == 0, "Clean previous fixed fixtures before prepare");
            }
            long normal = create(c), hot = create(c);
            Files.writeString(OUT.resolve("fixture.json"), JSON.writeValueAsString(Map.of("normalId", normal, "hotId", hot, "marker", marker)));
            clearTestCache();
            record("fixed-fixtures-prepared", Map.of("normalId", normal, "hotId", hot, "hotIds", "10," + hot, "redisDatabase", 15));
        }
    }

    static void cleanup() throws Exception {
        var f = fixture();
        marker = f.path("marker").asText();
        int deleted = 0;
        try (var c = db(); var statement = c.prepareStatement("DELETE FROM tb_shop WHERE id IN (?,?) AND name LIKE ?")) {
            statement.setLong(1, f.path("normalId").asLong());
            statement.setLong(2, f.path("hotId").asLong());
            statement.setString(3, marker + "%");
            deleted = statement.executeUpdate();
        }
        clearTestCache();
        record("fixtures-cleaned", Map.of("deletedRows", deleted, "versionTombstonesRetained", true));
    }

    static void cold100(long id, boolean fresh) throws Exception {
        var barrier = new CyclicBarrier(100);
        long before = loads(8081) + loads(8082);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> values = new ArrayList<>();
            for (int i = 0; i < 100; i++)
                values.add(callers.submit(() -> {
                    barrier.await();
                    return fresh ? response(8081, "/internal/shop-cache/" + id + "?fresh=true", true, null).path("snapshot").path("mode").asText().equals("HOT") : name(8081, id).equals(marker);
                }));
            for (var value : values) require(value.get(10, TimeUnit.SECONDS), "cold concurrent response");
        }
        long delta = loads(8081) + loads(8082) - before;
        require(delta == 1, "one payload JOIN for 100 cold requests");
        record(fresh ? "100-HOT-source-cold-requests" : "100-normal-cold-requests", Map.of("databasePayloadLoads", delta, "metadataInitiallyMissing", !fresh));
    }

    static void cdc() throws Exception {
        var f = fixture();
        marker = f.path("marker").asText();
        long normal = f.path("normalId").asLong(), id = f.path("hotId").asLong();
        try (var c = db()) {
            for (int p : new int[]{8080, 8081, 8082}) require(name(p, normal).equals(marker), "normal query");
            require(redis.ttl("cache:shop:v3:normal:" + normal) > 1700, "normal physical TTL30min");
            for (int p : new int[]{8080, 8081, 8082}) require(!resident(p, normal), "normal never stored upstairs");
            record("normal-only-Redis", Map.of("ttlSeconds", redis.ttl("cache:shop:v3:normal:" + normal)));
            redis.del("cache:shop:v3:normal:" + normal, "cache:shop:v3:meta:" + normal);
            cold100(normal, false);
            await(() -> {
                try {
                    return resident(8080, id) && resident(8081, id) && resident(8082, id);
                } catch (Exception e) {
                    return false;
                }
            }, 15000, "fixed HOT replicas prewarmed");
            require(redis.ttl("cache:shop:v3:hot:" + id) == -1, "HOT no Redis physical TTL");
            record("fixed-HOT-prewarm", Map.of("redisTtlSeconds", -1, "revision", snapshot(id).path("revision").asText(), "encodedRedisBytes", redis.get("cache:shop:v3:hot:" + id).getBytes(StandardCharsets.UTF_8).length));
            String busyLock = "lock:shop:rebuild:v3:" + id;
            await(() -> redis.exists(busyLock) == 0, 3000, "source unlocked before fault");
            redis.hset(busyLock, "e2e-blocker", "1");
            redis.pexpire(busyLock, 10000);
            redis.del("cache:shop:v3:hot:" + id);
            long busyBefore = loads(8081) + loads(8082), busyStarted = System.nanoTime();
            try {
                var blocked = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/internal/shop-cache/" + id + "?fresh=true"))
                        .header(InternalCacheAccess.HEADER, key).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                require(blocked.statusCode() == 503, "true miss lock timeout returns503");
                require(loads(8081) + loads(8082) == busyBefore, "no unguarded payload load");
                require(name(8080, id).equals(marker) && name(8082, id).equals(marker), "resident HOT remains available during lock contention");
                record("lock-timeout-and-old-value-availability", Map.of("status", 503, "elapsedMs", (System.nanoTime() - busyStarted) / 1000000, "databasePayloadLoads", 0));
            } finally {
                redis.hdel(busyLock, "e2e-blocker");
            }
            await(() -> {
                try {
                    return stats(8081).path("sourceInFlight").asInt() == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 3000, "timed-out task finished");
            redis.del("cache:shop:v3:hot:" + id);
            cold100(id, true);
            require(redis.ttl("cache:shop:v3:hot:" + id) == -1, "HOT cold rebuild remains permanent");
            Thread.sleep(60500);
            for (int p : new int[]{8080, 8081, 8082}) require(resident(p, id), "resident after logical expiry");
            record("resident-past-logical-deadline", Map.of("testLocalLogicalSeconds", 60, "edge", stats(8080), "jvm1", stats(8081), "jvm2", stats(8082)));
            var expired = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot(id);
            expired.put("logicalExpireAtEpochMillis", System.currentTimeMillis() - 1);
            redis.set("cache:shop:v3:hot:" + id, JSON.writeValueAsString(expired));
            long before = loads(8081) + loads(8082), started = System.nanoTime();
            for (int p : new int[]{8080, 8081, 8082})
                require(name(p, id).equals(marker), "stale returned during rebuild");
            long foregroundMs = (System.nanoTime() - started) / 1000000;
            await(() -> {
                try {
                    return snapshot(id).path("logicalExpireAtEpochMillis").asLong() > System.currentTimeMillis() + 1700000;
                } catch (Exception e) {
                    return false;
                }
            }, 8000, "expired source rebuilt");
            require(loads(8081) + loads(8082) - before == 1, "one payload load across JVMs");
            require(redis.ttl("cache:shop:v3:hot:" + id) == -1, "HOT remains permanent");
            record("logical-expiry-async-refresh", Map.of("databasePayloadLoads", 1, "threeForegroundReadsMs", foregroundMs));
            guardChecks(id);
            String revision = snapshot(id).path("revision").asText();
            started = System.nanoTime();
            try (var statement = c.prepareStatement("UPDATE tb_shop SET name=?,update_time=NOW(6) WHERE id=?")) {
                statement.setString(1, marker + "-updated");
                statement.setLong(2, id);
                statement.executeUpdate();
            }
            await(() -> {
                try {
                    return !snapshot(id).path("revision").asText().equals(revision) && JSON.readTree(snapshot(id).path("dataJson").asText()).path("name").asText().equals(marker + "-updated");
                } catch (Exception e) {
                    return false;
                }
            }, 20000, "CDC rebuilds HOT without reads");
            long proactiveMs = (System.nanoTime() - started) / 1000000;
            for (int p : new int[]{8080, 8081, 8082})
                await(() -> name(p, id).equals(marker + "-updated"), 5000, "updated replica");
            record("SQL-Canal-MQ-HOT-rebuild", Map.of("sourceUpdatedBeforeAnyReadMs", proactiveMs, "localLogicalTtlDuringTestSeconds", 60));
            try (var statement = c.prepareStatement("UPDATE tb_shop SET name=? WHERE id=?")) {
                statement.setString(1, marker + "-normal-updated");
                statement.setLong(2, normal);
                statement.executeUpdate();
            }
            await(() -> redis.get("cache:shop:v3:normal:" + normal) == null, 20000, "ordinary source invalidated without reads");
            for (int p : new int[]{8080, 8081, 8082}) {
                require(name(p, normal).equals(marker + "-normal-updated"), "ordinary updated");
                require(!resident(p, normal), "ordinary stays outside upper replicas");
            }
            record("SQL-Canal-MQ-NORMAL-invalidation", Map.of("allThreeResponsesUpdated", true, "noUpperReplica", true));
            try (var statement = c.prepareStatement("DELETE FROM tb_shop WHERE id=?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            await(() -> "1".equals(redis.hget("cache:shop:v3:meta:" + id, "deleted")), 20000, "DELETE reached CDC");
            for (int p : new int[]{8080, 8081, 8082})
                await(() -> {
                    try {
                        return !shop(p, id).path("success").asBoolean();
                    } catch (Exception e) {
                        return false;
                    }
                }, 5000, "deleted replica");
            String deletedVersion = redis.hget("cache:shop:v3:meta:" + id, "revision");
            require(redis.get("cache:shop:v3:hot:" + id) == null, "deleted HOT retired");
            require(redis.ttl("cache:shop:v3:normal:" + id) > 0 && redis.ttl("cache:shop:v3:normal:" + id) <= 120, "negative sentinel TTL");
            try (var statement = c.prepareStatement("INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,create_time,update_time) SELECT ?,?,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours,NOW(6),NOW(6) FROM tb_shop ORDER BY id LIMIT 1")) {
                statement.setLong(1, id);
                statement.setString(2, marker + "-recreated");
                statement.executeUpdate();
            }
            await(() -> "0".equals(redis.hget("cache:shop:v3:meta:" + id, "deleted")), 20000, "same ID inserted");
            for (int p : new int[]{8080, 8081, 8082})
                await(() -> name(p, id).equals(marker + "-recreated"), 5000, "recreated replica");
            require(ShopRedisStore.compareRevision(snapshot(id).path("revision").asText(), deletedVersion) > 0, "ID reuse monotonic version");
            require(snapshot(id).path("mode").asText().equals("HOT"), "reinserted fixed ID stays HOT");
            record("DELETE-and-fixed-HOT-ID-reuse", Map.of("deletedRevision", deletedVersion, "recreatedRevision", snapshot(id).path("revision").asText()));
            var removed = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/internal/shop-cache/policy/" + id))
                    .header(InternalCacheAccess.HEADER, key).POST(HttpRequest.BodyPublishers.ofString("{} ")).build(), HttpResponse.BodyHandlers.discarding());
            require(removed.statusCode() == 401 || removed.statusCode() == 404, "old mode API unavailable");
            require(Arrays.stream(com.hmdp.controller.InternalShopCacheController.class.getDeclaredMethods())
                    .noneMatch(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)), "no mode mutation mapping");
            record("no-runtime-mode-API", Map.of("httpStatus", removed.statusCode(), "noPostMapping", true));
            for (String target : new String[]{"http://127.0.0.1:8081/internal/shop-cache/stats", "http://127.0.0.1:8080/api/internal/shop-cache/stats"}) {
                var result = HTTP.send(HttpRequest.newBuilder(URI.create(target)).GET().build(), HttpResponse.BodyHandlers.discarding());
                require(result.statusCode() == 403 || result.statusCode() == 404, "internal API protected");
            }
            record("internal-API-protection", Map.of("withoutKey", 403, "edgeProxy", 404));
        }
    }

    static void guardChecks(long id) throws Exception {
        String lock = "lock:shop:rebuild:v3:" + id, meta = "cache:shop:v3:meta:" + id, data = "cache:shop:v3:hot:" + id;
        String original = redis.get(data), rev = redis.hget(meta, "revision");
        String script = Files.readString(ROOT.resolve("src/main/resources/lua/publish-shop-cache.lua"));
        await(() -> redis.exists(lock) == 0, 3000, "source worker unlocked");
        Long rejected = redis.eval(script, io.lettuce.core.ScriptOutputType.INTEGER, new String[]{lock, meta, data}, "wrong-owner", rev, "HOT", original, "1800", "test-only-channel", "{}");
        require(rejected != 1, "lost lock rejects publication");
        // 自有fixture且源刷新完成后，短时创建专用模拟owner；finally只删自己owner字段。
        redis.hset(lock, "e2e-owner", "1");
        redis.pexpire(lock, 10000);
        try {
            rejected = redis.eval(script, io.lettuce.core.ScriptOutputType.INTEGER, new String[]{lock, meta, data}, "e2e-owner", "0", "HOT", original, "1800", "test-only-channel", "{}");
            require(rejected != 1, "old version rejects publication");
        } finally {
            redis.hdel(lock, "e2e-owner");
        }
        require(original.equals(redis.get(data)), "rejected publish preserved source");
        record("atomic-publish-guards", Map.of("lostOwnerRejected", true, "oldVersionRejected", true));
    }

    static void retry() throws Exception {
        var status = new AtomicInteger(503);
        var edge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        edge.createContext("/shop/", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        edge.start();
        var props = new ShopCacheProperties();
        props.setEdgeInvalidateUrls("http://127.0.0.1:" + edge.getAddress().getPort() + "/shop");
        var notifier = new ShopCacheNotifier(props, new InternalCacheAccess(props));
        var producer = new DefaultMQProducer("hmdp-fixed-e2e-" + UUID.randomUUID());
        producer.setNamesrvAddr("127.0.0.1:9876");
        producer.setVipChannelEnabled(false);
        producer.start();
        try {
            for (boolean recover : new boolean[]{true, false}) {
                String tag = UUID.randomUUID().toString(), group = "hmdp-fixed-e2e-" + (recover ? "retry-" : "dlq-") + tag.substring(0, 8);
                var failures = new AtomicInteger();
                var successes = new AtomicInteger();
                var retry = new AtomicInteger();
                var consumer = new DefaultMQPushConsumer(group);
                consumer.setNamesrvAddr("127.0.0.1:9876");
                consumer.setVipChannelEnabled(false);
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
                consumer.setMaxReconsumeTimes(1);
                consumer.setConsumeThreadMin(1);
                consumer.setConsumeThreadMax(1);
                consumer.subscribe("hmdp-shop-change-e2e", tag);
                consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                    retry.set(messages.getFirst().getReconsumeTimes());
                    try {
                        notifier.edges(10);
                        successes.incrementAndGet();
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                });
                status.set(503);
                consumer.start();
                try {
                    producer.send(new Message("hmdp-shop-change-e2e", tag, tag, "notification-component-test".getBytes(StandardCharsets.UTF_8)));
                    await(() -> failures.get() > 0, 20000, "real failed delivery");
                    if (recover) {
                        status.set(200);
                        await(() -> successes.get() > 0, 40000, "real Broker retry recovered");
                        require(retry.get() >= 1, "retry counter");
                    } else {
                        await(() -> failures.get() >= 2, 40000, "retry limit reached");
                        require(successes.get() == 0, "no false ACK");
                        Files.writeString(OUT.resolve("dlq-topic.txt"), "%DLQ%" + group);
                    }
                    record(recover ? "MQ-notification-retry-ACK" : "MQ-permanent-failure-DLQ", Map.of("failures", failures.get(), "successes", successes.get(), "reconsumeTimes", retry.get(), "group", group));
                } finally {
                    consumer.shutdown();
                }
            }
        } finally {
            producer.shutdown();
            edge.stop(0);
        }
    }

    static String match(String value, String pattern) {
        var m = Pattern.compile(pattern).matcher(value);
        if (!m.find()) throw new IllegalArgumentException("Missing configuration");
        return m.group(1).trim();
    }

    static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    static void await(BooleanSupplier predicate, long ms, String description) throws Exception {
        long end = System.nanoTime() + ms * 1000000;
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timeout: " + description);
            Thread.sleep(50);
        }
    }

    static void record(String name, Map<String, Object> details) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("check", name);
        row.putAll(details);
        CHECKS.add(row);
        System.out.println("CHECK " + JSON.writeValueAsString(row));
    }
}
