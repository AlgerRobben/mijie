package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 验证监听器遇失效失败/坏JSON返回重试，重复删除后可成功确认；Broker真实重投另由deploy联调验证。
 */
class ShopChangeMessageListenerTest {
    @Test
    void failedInvalidationIsRetriedAndRepeatedDeleteCanSucceed() {
        ShopCacheService cache = mock(ShopCacheService.class);
        doThrow(new IllegalStateException("edge unavailable")).doNothing().when(cache).evictReliably(10L);
        ShopChangeMessageListener listener = new ShopChangeMessageListener(new ShopChangeHandler(new ObjectMapper(), cache));
        MessageExt message = message("{\"database\":\"hmdp\",\"table\":\"tb_shop\",\"type\":\"UPDATE\",\"data\":[{\"id\":10}]}");
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(List.of(message), null));
        message.setReconsumeTimes(1);
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, listener.consumeMessage(List.of(message), null));
        verify(cache, times(2)).evictReliably(10L);
    }

    @Test
    void malformedJsonIsRetriedForBrokerDeadLetterHandling() {
        ShopCacheService cache = mock(ShopCacheService.class);
        ShopChangeMessageListener listener = new ShopChangeMessageListener(new ShopChangeHandler(new ObjectMapper(), cache));
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(List.of(message("broken")), null));
    }

    private static MessageExt message(String body) {
        MessageExt message = new MessageExt();
        message.setMsgId("test-event");
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
