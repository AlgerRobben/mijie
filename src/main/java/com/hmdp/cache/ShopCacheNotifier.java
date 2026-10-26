package com.hmdp.cache;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 边缘接口只标记HOT需要异步刷新/处理退役，不删除正常HOT正文。HTTP失败由MQ可靠重试。
 */
@Component
public class ShopCacheNotifier {
    private final ShopCacheProperties properties;
    private final InternalCacheAccess access;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    public ShopCacheNotifier(ShopCacheProperties properties, InternalCacheAccess access) {
        this.properties = properties;
        this.access = access;
    }

    public void edges(long id) {
        List<Exception> failures = new ArrayList<>();
        for (String configured : properties.getEdgeInvalidateUrls().split(",")) {
            String base = configured.trim();
            if (base.isEmpty()) continue;
            try {
                String url = base + (base.endsWith("/") ? "" : "/") + id;
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2))
                        .header(InternalCacheAccess.HEADER, access.key()).POST(HttpRequest.BodyPublishers.noBody()).build();
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200)
                    failures.add(new IllegalStateException("Edge notification was rejected"));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException error = new IllegalStateException("Edge notification failed for shop " + id);
            failures.forEach(error::addSuppressed);
            throw error;
        }
    }
}
