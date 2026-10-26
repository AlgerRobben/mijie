import cn.hutool.json.JSONUtil;
import com.hmdp.utils.RedisData;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
/**
 * 只读逻辑过期诊断工具，核对原缓存日期解析，并构造纳秒/毫秒对照样本。
 * 入口：独立java -cp调用，依赖target/classes中的RedisData和既有库；结果写入诊断JSON，不修改缓存。
 */
// 保留旧RedisData日期解析问题的诊断。它不读取新版HOT快照；新版以毫秒期限验收。
public class InspectLogical {
 static String match(String t,String r){var m=Pattern.compile(r).matcher(t);m.find();return m.group(1).trim();}
 /**
  * 独立命令行入口。参数与classpath由本工具的启动脚本/使用说明提供，运行前核对目标环境和本类注明的数据副作用。
  */
 public static void main(String[] args)throws Exception {
  String y=Files.readString(Path.of("src/main/resources/application.yaml"));String ry=y.substring(y.indexOf("  redis:"),y.indexOf("  jackson:"));
  var client=RedisClient.create(RedisURI.Builder.redis(match(ry,"(?m)^    host: (.+)$"),Integer.parseInt(match(ry,"(?m)^    port: (.+)$"))).withDatabase(15).withPassword(match(ry,"(?m)^    password: (.+)$").toCharArray()).build());
  try(var c=client.connect()) {
   String raw=args.length>2?new ObjectMapper().readTree(Files.readString(Path.of(args[2]))).path("raw").asText():c.sync().get("cache:shop:"+args[0]);var p=JSONUtil.toBean(raw,RedisData.class);Map<String,Object> result=new LinkedHashMap<>();
   result.put("raw",raw);result.put("zone",ZoneId.systemDefault().toString());result.put("now",LocalDateTime.now().toString());result.put("parsedExpireTime",p.getExpireTime().toString());result.put("localExpired",p.getExpireTime().isBefore(LocalDateTime.now()));result.put("epochExpired",p.getExpireAtEpochMillis()<System.currentTimeMillis());result.put("lockTtl",c.sync().pttl("lock:shop:"+args[0]));result.put("lockValue",String.valueOf(c.sync().get("lock:shop:"+args[0])));
   var mapper=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
   var highPrecision=new RedisData();highPrecision.setExpireTime(LocalDateTime.now().minusSeconds(30).withNano(123456789));highPrecision.setExpireAtEpochMillis(System.currentTimeMillis()-30000);highPrecision.setData(Map.of("id",args[0]));
   String serialized=mapper.writeValueAsString(highPrecision);var decoded=JSONUtil.toBean(serialized,RedisData.class);
   result.put("jacksonSerializedControl",serialized);result.put("jacksonControlParsedExpireTime",decoded.getExpireTime().toString());result.put("jacksonControlExpired",decoded.getExpireTime().isBefore(LocalDateTime.now()));
   highPrecision.setExpireTime(highPrecision.getExpireTime().withNano(123000000));String millis=mapper.writeValueAsString(highPrecision);result.put("millisecondControl",millis);result.put("millisecondControlExpired",JSONUtil.toBean(millis,RedisData.class).getExpireTime().isBefore(LocalDateTime.now()));
   Files.writeString(Path.of(args[1]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }finally{client.shutdown();}
 }
}
