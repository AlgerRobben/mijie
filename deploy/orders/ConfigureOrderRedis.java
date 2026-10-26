import io.lettuce.core.*;
import java.nio.file.*;
import java.util.regex.*;

/**
 * 真实Redis持久化设置工具，业务应用启动不会自动执行它。
 * 入口：deploy/orders/configure-redis-durability.ps1；读取既有连接配置。
 * 会改变appendonly/appendfsync并CONFIG REWRITE持久保存，操作的是实际依赖，不是Mock。
 */
public class ConfigureOrderRedis {
    static String get(String text,String pattern){var match=Pattern.compile(pattern).matcher(text);if(!match.find())throw new IllegalArgumentException("Missing Redis setting");return match.group(1).trim();}
    /**
     * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
     */
    public static void main(String[] args) throws Exception {
        String yaml=Files.readString(Path.of("src/main/resources/application.yaml"));
        yaml=yaml.substring(yaml.indexOf("  redis:"),yaml.indexOf("  jackson:"));
        RedisClient client=RedisClient.create(RedisURI.Builder.redis(get(yaml,"(?m)^    host: (.+)$"),Integer.parseInt(get(yaml,"(?m)^    port: (.+)$")))
                .withPassword(get(yaml,"(?m)^    password: (.+)$").toCharArray()).build());
        try(var connection=client.connect()) {
            var redis=connection.sync();
            System.out.println("before="+redis.configGet("appendonly")+" "+redis.configGet("appendfsync"));
            redis.configSet("appendfsync","always"); redis.configSet("appendonly","yes");
            long deadline=System.nanoTime()+60_000_000_000L;
            while(redis.info("persistence").contains("aof_rewrite_in_progress:1")) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("AOF rewrite still running; inspect Redis persistence before accepting orders");
                Thread.sleep(200);
            }
            if(!redis.info("persistence").contains("aof_last_bgrewrite_status:ok"))throw new IllegalStateException("AOF rewrite failed");
            redis.configRewrite();
            System.out.println("after="+redis.configGet("appendonly")+" "+redis.configGet("appendfsync")+"; CONFIG REWRITE succeeded");
        } finally {client.shutdown();}
    }
}
