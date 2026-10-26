package com.gmd.faq.server.main.websocket.cluster;

import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 描述：Redis 集群会话管理器 - 实现跨节点会话共享
 *
 **/
@Slf4j
@Component
public class RedisSessionManager {
    private final MessagePublisher messagePublisher;

    public RedisSessionManager(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    /**
     * 广播消息到所有节点
     *
     * @param channel 频道
     * @param message 消息内容
     */
    public void broadcastMessage(String channel, String message) {
        try {
            messagePublisher.publish(channel, message);
        } catch (Exception e) {
            log.error("广播消息失败：{}", e.getMessage(), e);
        }
    }
}
