package com.hmdp.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RocketMQ消费回调。UTF-8解码后交给ShopChangeHandler；整批成功才返回CONSUME_SUCCESS。
 * 异常返回RECONSUME_LATER，延迟重投和超过上限后的Broker DLQ由RocketMQ负责，业务代码不自行吞掉失败消息。
 */
@Slf4j
@Component
public class ShopChangeMessageListener implements MessageListenerConcurrently {
    private final ShopChangeHandler handler;

    public ShopChangeMessageListener(ShopChangeHandler handler) {
        this.handler = handler;
    }

    /**
     * 按UTF-8读取消息，逐条执行失效；只要一条失败，整批返回RECONSUME_LATER。
     * 已清理部分可以再次执行，因为删除/广播/边缘删除可重复；重试次数读取Broker的reconsumeTimes。
     */
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            try {
                // canal以JSON格式传递字节数组，此处再以UTF-8规则转成字符串
                handler.handle(new String(message.getBody(), StandardCharsets.UTF_8));
                log.info("SHOP_CACHE_CDC_ACK msgId={} retries={}", message.getMsgId(), message.getReconsumeTimes());
            } catch (Exception e) {
                log.error("SHOP_CACHE_CDC_RETRY msgId={} retries={}，达到上限后进入 Broker 死信队列",
                        message.getMsgId(), message.getReconsumeTimes(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
