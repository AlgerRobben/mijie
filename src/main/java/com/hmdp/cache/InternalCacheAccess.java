package com.hmdp.cache;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.io.IOException;

/**
 * 内部维护/回源接口同时检查本机来源和共享密钥，防普通/api代理把远端请求变成loopback后绕过权限。
 * 多实例读同一个被.gitignore忽略的文件；密钥不写入源码或Java进程命令行。
 */
@Component
public class InternalCacheAccess {
    public static final String HEADER = "X-HMDP-Internal-Key";
    private final String key;
    public InternalCacheAccess(ShopCacheProperties properties) {
        try {
            Path path = Path.of(properties.getInternalKeyFile()).toAbsolutePath();
            Files.createDirectories(path.getParent());
            try { Files.writeString(path, UUID.randomUUID().toString().replace("-", ""),
                    StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { /* 并发启动时复用先创建者的密钥。 */ }
            key = Files.readString(path).trim();
            if (key.length() < 32) throw new IllegalStateException("Internal cache key must contain at least 32 characters");
        } catch (IOException e) { throw new IllegalStateException("Cannot load internal cache key", e); }
    }
    public String key() { return key; }
    public void check(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        String supplied = request.getHeader(HEADER);
        boolean local = "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
        if (!local || supplied == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
