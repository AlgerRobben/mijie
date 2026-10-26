package com.hmdp.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证Result等record的JSON字段保持接口约定；关注序列化结构，不验证HTTP网络/业务事务。
 */
class ResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void responseRecordsKeepTheExistingJsonFields() throws Exception {
        ScrollResult scroll = new ScrollResult(List.of("blog"), 123L, 2);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(Result.ok(scroll)));

        assertTrue(json.path("success").asBoolean());
        assertTrue(json.get("errorMsg").isNull());
        assertTrue(json.get("total").isNull());
        assertEquals("blog", json.path("data").path("list").get(0).asText());
        assertEquals(123L, json.path("data").path("minTime").asLong());
        assertEquals(2, json.path("data").path("offset").asInt());
    }
}
