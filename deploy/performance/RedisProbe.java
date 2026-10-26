import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/**
 * 只读Redis时钟和持久化配置探测工具，不参与订单受理。
 * 入口：性能编排的Probe函数；从工程配置连接实际Redis，将TIME往返估计与append配置写入结果文件。
 */
public class RedisProbe {
    static String match(String text,String regex){var m=Pattern.compile(regex).matcher(text);if(!m.find())throw new IllegalArgumentException("Missing Redis property");return m.group(1).trim();}
    /**
     * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
     */
    public static void main(String[] args)throws Exception {
        String yaml=Files.readString(Path.of("src/main/resources/application.yaml"));String ry=yaml.substring(yaml.indexOf("  redis:"),yaml.indexOf("  jackson:"));
        var client=RedisClient.create(RedisURI.Builder.redis(match(ry,"(?m)^    host: (.+)$"),Integer.parseInt(match(ry,"(?m)^    port: (.+)$"))).withDatabase(15).withPassword(match(ry,"(?m)^    password: (.+)$").toCharArray()).build());
        try(var connection=client.connect()) {
            var redis=connection.sync();double best=Double.POSITIVE_INFINITY,offset=0,remote=0;long epoch=0;
            for(int i=0;i<20;i++){long before=System.currentTimeMillis(),nano=System.nanoTime();var t=redis.time();double elapsed=(System.nanoTime()-nano)/1e6;double time=Long.parseLong(t.get(0))*1000d+Long.parseLong(t.get(1))/1000d;if(elapsed<best){best=elapsed;offset=before+elapsed/2-time;epoch=before;remote=time;}}
            Map<String,Object> result=new LinkedHashMap<>();result.put("hostEpochMs",epoch);result.put("redisEpochMs",remote);result.put("hostMinusRedisMs",offset);result.put("minRttMs",best);result.put("config",redis.configGet("append*"));result.put("dbSize",redis.dbsize());
            Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }finally{client.shutdown();}
    }
}
