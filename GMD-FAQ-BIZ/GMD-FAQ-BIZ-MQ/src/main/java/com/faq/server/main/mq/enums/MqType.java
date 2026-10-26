package com.gmd.faq.server.main.mq.enums;

/**
 * 描述：消息队列类型
 *
 **/
public enum MqType {
    KAFKA("kafka"),
    RABBITMQ("rabbitmq"),
    ROCKETMQ("rocketmq");

    MqType(String desc) {}
}
