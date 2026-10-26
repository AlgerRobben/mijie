package com.hmdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 解析Canal flat JSON，只处理hmdp商铺/缓存版本表的INSERT/UPDATE/DELETE。
 * 商铺主键不更新：只从当前行提取商铺ID，逐个按固定冷热分类失效或刷新；不维护GEO等其他缓存。坏事件抛出异常让MQ重试。
 */
@Slf4j
@Service
public class ShopChangeHandler {
    private final ObjectMapper mapper;
    private final ShopCacheService cache;

    public ShopChangeHandler(ObjectMapper mapper, ShopCacheService cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /**
     * body是Canal flat JSON消息文本。先验证JSON与目标库表/DML类型，再从data提取主键并去重。
     * 商铺主键视为不可变，版本表使用shop_id；old只包含UPDATE前的变化字段，无需读取。
     * 协调器核对当前事实后处理，不直接写消息旧payload。
     * 严格协调失败抛出异常，由监听器返回RECONSUME_LATER；HOT边缘注册异步任务不等于刷新已经完成。
     */
    public void handle(String body) {
        JsonNode message;
        // 将String类型的JSON字符串转成JSON树对象
        try {
            message = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法解析 Canal JSON，交由 MQ 重试/死信处理", e);
        }
        // 先验证JSON根结构，再过滤非目标库表和非DML事件。
        if (message == null || !message.isObject()) {
            throw new IllegalArgumentException("Canal 消息必须是 JSON 对象");
        }
        if (!"hmdp".equalsIgnoreCase(message.path("database").asText())
                || !("tb_shop".equalsIgnoreCase(message.path("table").asText())
                || "tb_shop_cache_version".equalsIgnoreCase(message.path("table").asText()))
                || message.path("isDdl").asBoolean()) {
            return;
        }
        String type = message.path("type").asText();
        if (!"INSERT".equalsIgnoreCase(type) && !"UPDATE".equalsIgnoreCase(type)
                && !"DELETE".equalsIgnoreCase(type)) {
            return;
        }
        // 取JSON消息变更数据
        JsonNode rows = message.path("data");
        if (!rows.isArray()) {
            throw new IllegalArgumentException("商铺变更消息缺少 data 数组");
        }
        Set<Long> ids = new LinkedHashSet<>();
        // 读取受影响商铺ID；版本表使用shop_id，商铺表使用id。
        for (JsonNode row : rows) {
            ids.add(parseId(row.has("shop_id") ? row.path("shop_id") : row.path("id")));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("商铺变更消息没有可失效的 ID");
        }
        for (Long id : ids) {
            cache.evictReliably(id);
        }
        log.info("SHOP_CACHE_CDC_APPLIED eventId={} type={} shopIds={}", message.path("id").asText(), type, ids);
    }

    /**
     * 使用Long.parseLong解析数字文本，不通过浮点数转换大整数。缺失、格式错误、非正ID均拒绝，防止坏消息被静默ACK。
     */
    private static long parseId(JsonNode node) {
        try {
            long id = Long.parseLong(node.asText());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 拒绝不合法ID，让监听器选择重试；不把坏事件默默确认掉。
        }
        throw new IllegalArgumentException("商铺变更消息包含无效 ID");
    }
}
