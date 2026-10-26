import com.fasterxml.jackson.databind.*;
import io.lettuce.core.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/**
 * 只读测试数据清理审计工具，不参与订单补偿业务。
 * 入口：独立java -cp调用，以run目录manifest核对对应SQL行和Redis键剩余数量。
 * 检查测试结果是否已清理，审计失败抛异常；不能把该程序作为生产批量删除入口。
 */
public class AuditCleanup {
    static final ObjectMapper JSON = new ObjectMapper();

    static String match(String s, String regex) {
        var m = Pattern.compile(regex).matcher(s);
        if (!m.find()) throw new IllegalArgumentException("Missing property");
        return m.group(1).trim();
    }

    /**
     * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
     */
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        // 历史报告使用15号库，当前V4复测显式传入14；审计只读，不能默默检查错库。
        int database = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        var manifest = JSON.readTree(Files.readString(run.resolve("manifest.json")));
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        String ry = yaml.substring(yaml.indexOf("  redis:"), yaml.indexOf("  jackson:"));
        var client = RedisClient.create(RedisURI.Builder.redis(match(ry, "(?m)^    host: (.+)$"), Integer.parseInt(match(ry, "(?m)^    port: (.+)$"))).withDatabase(database).withPassword(match(ry, "(?m)^    password: (.+)$").toCharArray()).build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redisDatabase", database);
        List<String> keys = new ArrayList<>();
        try (var r = client.connect(); var db = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", match(yaml, "(?m)^    password: (.+)$"))) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String table : List.of("tb_shop", "tb_voucher", "tb_seckill_voucher", "tb_voucher_order", "tb_seckill_order_task")) {
                var ids = table.equals("tb_shop") ? manifest.path("shops") : manifest.path("vouchers");
                String column = table.equals("tb_shop") || table.equals("tb_voucher") ? "id" : "voucher_id";
                String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
                try (var s = db.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + " IN (" + placeholders + ")")) {
                    int i = 1;
                    for (var id : ids) s.setLong(i++, id.asLong());
                    try (var rows = s.executeQuery()) {
                        rows.next();
                        counts.put(table, rows.getLong(1));
                    }
                }
            }
            for (var id : manifest.path("shops"))
                for (String prefix : List.of("cache:shop:", "lock:shop:", "cache:shop:normal:", "cache:shop:hot:", "lock:shop:rebuild:v2:", "cache:shop:v3:normal:", "cache:shop:v3:hot:", "lock:shop:rebuild:v3:"))
                    keys.add(prefix + id.asLong());
            // 新版meta/数据库版本删除记录用于防止ID复用的旧消息回流，不能当作未清理业务正文。
            for (var id : manifest.path("vouchers"))
                for (String prefix : List.of("seckill:stock:", "seckill:begin:", "seckill:end:", "seckill:order:", "seckill:reservation:", "seckill:compensated:"))
                    keys.add(prefix + id.asLong());
            for (var user : manifest.path("users")) keys.add("login:token:" + user.path("token").asText());
            for (String line : Files.readAllLines(run.resolve("order-observations.jsonl")))
                if (!line.isBlank()) keys.add("seckill:result:" + JSON.readTree(line).path("orderId").asText());
            String stream = manifest.path("stream").asText();
            keys.addAll(List.of(stream, stream + ":dlq", stream + ":archive"));
            long remaining = 0;
            for (int i = 0; i < keys.size(); i += 500)
                remaining += r.sync().exists(keys.subList(i, Math.min(i + 500, keys.size())).toArray(String[]::new));
            result.put("sqlRemainingRows", counts);
            result.put("ownedRedisKeysChecked", keys.size());
            result.put("ownedRedisKeysRemaining", remaining);
            result.put("redisDatabaseSize", r.sync().dbsize());
            List<Map<String, Object>> inventory = new ArrayList<>();
            for (String key : r.sync().scan(ScanArgs.Builder.limit(100)).getKeys())
                inventory.add(Map.of("key", key.startsWith("login:token:") ? "login:token:<existing>" : key, "ttlSeconds", r.sync().ttl(key)));
            result.put("remainingInventory", inventory);
            result.put("passed", remaining == 0 && counts.values().stream().allMatch(x -> x == 0));
            Files.writeString(run.resolve("cleanup-audit.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            if (!Boolean.TRUE.equals(result.get("passed"))) throw new IllegalStateException("Cleanup audit failed");
        } finally {
            client.shutdown();
        }
    }
}
