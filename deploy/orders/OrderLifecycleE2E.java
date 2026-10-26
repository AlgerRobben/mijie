import com.fasterxml.jackson.databind.*;
import io.lettuce.core.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * 工程HTTP订单联调工具源码，正常业务请求不会调用本类。
 * 入口：deploy/orders/verify-end-to-end.ps1；依赖独立8083实例、MySQL和Redis15号库。
 * 作用：受理/归属/成功/最终失败补偿/迟到消息验证；会写专用券、订单、任务和token，finally按清单清理。
 */
public class OrderLifecycleE2E {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<Long> vouchers = new ArrayList<>(), orders = new ArrayList<>();
    static final List<Map<String,Object>> checks = new ArrayList<>();
    static final long userId = 8_000_000_000_000L + Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000);
    static String token = "order-e2e-" + UUID.randomUUID(), otherToken = "order-e2e-other-" + UUID.randomUUID();
    static String stream, dlq, archive;
    static io.lettuce.core.api.sync.RedisCommands<String,String> redis;
    static Connection db;

    /**
     * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
     */
    public static void main(String[] args) throws Exception {
        stream = args[0]; dlq = args[1]; archive = args[2];
        String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        String dbPassword = match(yaml, "(?m)^    password: (.+)$");
        String ry = yaml.substring(yaml.indexOf("  redis:"), yaml.indexOf("  jackson:"));
        RedisClient client = RedisClient.create(RedisURI.Builder.redis(match(ry, "(?m)^    host: (.+)$"),
                Integer.parseInt(match(ry,"(?m)^    port: (.+)$"))).withDatabase(15)
                .withPassword(match(ry,"(?m)^    password: (.+)$").toCharArray()).build());
        try (var connection = client.connect(); var database = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", dbPassword)) {
            db=database; redis=connection.sync();
            try {
                redis.hset("login:token:" + token, Map.of("id",Long.toString(userId),"nickName","Order E2E"));
                redis.expire("login:token:"+token,120);
                redis.hset("login:token:" + otherToken, Map.of("id",Long.toString(userId+1),"nickName","Other E2E"));
                redis.expire("login:token:"+otherToken,120);
                long valid = voucher(1, 1);
                long successfulId = accept(valid); awaitState(successfulId, "SUCCEEDED");
                require(count(valid) == 1 && stock(valid) == 0 && "0".equals(redis.get("seckill:stock:"+valid)),"successful order deducted stock once");
                check("HTTP accepted -> real consumer -> committed SUCCEEDED", Map.of("orderId",Long.toString(successfulId)));
                JsonNode denied = request("GET", "/voucher-order/status/"+successfulId, otherToken);
                require(!denied.path("success").asBoolean() && !denied.has("data"),"other user cannot query the order");
                check("status endpoint verifies ownership", Map.of());

                long broken = voucher(0, 1); // Deliberate dedicated-fixture mismatch forces permanent MySQL rejection.
                long failedId = accept(broken); awaitState(failedId, "FAILED");
                require(count(broken)==0 && stock(broken)==0,"failed MySQL transaction did not create order or add database stock");
                require("1".equals(redis.get("seckill:stock:"+broken)) && !redis.sismember("seckill:order:"+broken, Long.toString(userId)),"Redis reservation released");
                await(() -> redis.xlen(dlq)==0 && redis.xlen(archive)==1, 5000, "compensation archived");
                check("permanent write failure -> DLQ -> fenced compensation -> FAILED", Map.of("orderId",Long.toString(failedId),"refundedRedisStock",1));

                try(PreparedStatement update=db.prepareStatement("UPDATE tb_seckill_voucher SET stock=1 WHERE voucher_id=?")) {update.setLong(1,broken); update.executeUpdate();}
                long replacementId = accept(broken); awaitState(replacementId,"SUCCEEDED");
                redis.xadd(stream,Map.of("id",Long.toString(failedId),"userId",Long.toString(userId),"voucherId",Long.toString(broken)));
                Thread.sleep(2500);
                await(() -> redis.xpending(stream,"g1").getCount()==0,5000,"late replay acknowledged");
                require(count(broken)==1 && stock(broken)==0 && "0".equals(redis.get("seckill:stock:"+broken)),"late failed order did not create/refund again");
                require(Long.toString(replacementId).equals(redis.hget("seckill:reservation:"+broken,Long.toString(userId))),"late replay preserved newer reservation");
                awaitState(failedId,"FAILED");
                check("released user can retry; old failed message is fenced", Map.of("replacementId",Long.toString(replacementId)));
            } finally {
                for(long voucher:vouchers) {
                    for(String table:List.of("tb_voucher_order","tb_seckill_order_task","tb_seckill_voucher")) {
                        try(PreparedStatement delete=db.prepareStatement("DELETE FROM "+table+" WHERE voucher_id=?")){delete.setLong(1,voucher);delete.executeUpdate();}
                    }
                    try(PreparedStatement delete=db.prepareStatement("DELETE FROM tb_voucher WHERE id=? AND title LIKE 'order-e2e-%'")){delete.setLong(1,voucher);delete.executeUpdate();}
                    redis.del("seckill:stock:"+voucher,"seckill:begin:"+voucher,"seckill:end:"+voucher,"seckill:order:"+voucher,
                            "seckill:reservation:"+voucher,"seckill:compensated:"+voucher);
                }
                for(long order:orders) redis.del("seckill:result:"+order);
                redis.del("login:token:"+token,"login:token:"+otherToken);
                redis.del(stream,dlq,archive);
                Files.writeString(Path.of(".local/cache-sync/order-http-e2e-result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checks));
            }
        } finally {client.shutdown();}
        System.out.println("PASS HTTP order lifecycle checks; test vouchers, orders and reservations removed");
    }
    static long voucher(int dbStock,int redisStock) throws Exception {
        long id;
        try(PreparedStatement insert=db.prepareStatement("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type) VALUES (1,?,1,1,1)",Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1,"order-e2e-"+UUID.randomUUID());insert.executeUpdate();try(ResultSet keys=insert.getGeneratedKeys()){keys.next();id=keys.getLong(1);}
        }
        vouchers.add(id);
        try(PreparedStatement insert=db.prepareStatement("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,?,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)")){insert.setLong(1,id);insert.setInt(2,dbStock);insert.executeUpdate();}
        long now=System.currentTimeMillis();redis.mset(Map.of("seckill:stock:"+id,Integer.toString(redisStock),"seckill:begin:"+id,Long.toString(now-3600000),"seckill:end:"+id,Long.toString(now+3600000)));
        return id;
    }
    static long accept(long voucher) throws Exception {
        JsonNode result=request("POST","/voucher-order/seckill/"+voucher,token);
        require(result.path("success").asBoolean() && result.path("data").isTextual(),"accepted ID is an exact string");
        long id=Long.parseLong(result.path("data").asText());orders.add(id);return id;
    }
    static JsonNode request(String method,String path,String auth) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8083"+path)).timeout(Duration.ofSeconds(5)).header("authorization",auth);
        request.method(method,method.equals("POST")?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.noBody());
        var response=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());require(response.statusCode()==200,"HTTP 200");return JSON.readTree(response.body());
    }
    static void awaitState(long id,String state) throws Exception {
        await(() -> {try{return state.equals(request("GET","/voucher-order/status/"+id,token).path("data").path("state").asText());}catch(Exception e){return false;}},15000,"order state "+state);
    }
    static int count(long voucher) throws Exception {return scalar("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",voucher);}
    static int stock(long voucher) throws Exception {return scalar("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",voucher);}
    static int scalar(String sql,long voucher) throws Exception {try(PreparedStatement statement=db.prepareStatement(sql)){statement.setLong(1,voucher);try(ResultSet result=statement.executeQuery()){result.next();return result.getInt(1);}}}
    interface Check {boolean ready() throws Exception;}
    static void await(Check check,long ms,String name) throws Exception {long end=System.nanoTime()+ms*1_000_000;while(!check.ready()){if(System.nanoTime()>end)throw new AssertionError("Timed out: "+name);Thread.sleep(100);}}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void check(String name,Map<String,Object> values){Map<String,Object> entry=new LinkedHashMap<>();entry.put("check",name);entry.putAll(values);checks.add(entry);System.out.println("CHECK "+entry);}
    static String match(String text,String regex){Matcher match=Pattern.compile(regex).matcher(text);if(!match.find())throw new IllegalArgumentException("Missing local config");return match.group(1).trim();}
}
