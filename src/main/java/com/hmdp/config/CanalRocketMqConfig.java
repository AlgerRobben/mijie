package com.hmdp.config;

import com.hmdp.service.ShopChangeMessageListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * 按hmdp.canal.enabled启用商铺变更消费者。集群模式让同组一个实例处理消息，本地缓存另靠Pub/Sub广播。
 * 批量消费大小1、线程数1～4、默认最多重试8次；从首次消费位点策略启动，已存在的消费进度仍由Broker管理。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "hmdp.canal", name = "enabled", havingValue = "true")
public class CanalRocketMqConfig {
    /**
     * 创建按配置主题订阅的集群消费者，注册业务监听器；Spring在Bean初始化/销毁时自动start/shutdown。
     * 同组多实例分担消息，不是广播给每个JVM；其他JVM依靠Redis Pub/Sub清本地缓存。
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer shopChangeConsumer(
            @Value("${hmdp.canal.name-server}") String nameServer,
            @Value("${hmdp.canal.topic}") String topic,
            @Value("${hmdp.canal.consumer-group}") String group,
            @Value("${hmdp.canal.max-reconsume-times:8}") int maxReconsumeTimes, // 最大重试次数
            ShopChangeMessageListener listener) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(nameServer); // 设置nameserver地址
        consumer.setInstanceName("hmdp-" + UUID.randomUUID()); // 设置当前消费者实例名
        // 集群是同组分担消费；本地缓存不靠每个节点都收到MQ，而靠后续Redis广播失效。
        consumer.setMessageModel(MessageModel.CLUSTERING); // 集群消费
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMaxReconsumeTimes(maxReconsumeTimes); // 最大消费重试次数，超过次数转入死信队列
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(4);
        consumer.setVipChannelEnabled(false);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener(listener);
        return consumer;
    }
}
