package com.gmd.faq.server.main.websocket.cluster;

import com.gmd.faq.server.main.common.cluster.core.ChannelSubscriber;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.websocket.config.GmdWebSocketSessionManager;
import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 描述：Redis 消息订阅者 - 仅处理 gmd-faq:ws:cluster:* 频道的跨节点消息
 *
 **/
@Slf4j
@Component
public class ClusterMessageSubscriber implements ChannelSubscriber {

    @Override
    public Topic getTopic() {
        return new PatternTopic(RedisChannelTopic.WS_CHANNEL_PATTERN);
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            // 仅处理 gmd-faq:ws:cluster:* 频道，拒绝非 WebSocket 集群消息
            String subChannel = RedisChannelTopic.WS_CHANNEL_PATTERN.substring(0, RedisChannelTopic.WS_CHANNEL_PATTERN.length() - 1);
            if (!channel.startsWith(subChannel)) {
                log.warn("忽略非 WebSocket 集群频道的消息：channel={}", channel);
                return;
            }

            log.debug("接收到集群消息：channel={}", channel);

            ClusterMessage clusterMessage = JsonUtils.parse(message, ClusterMessage.class);

            // 构建消息对象
            WsServerMessage wsMessage = new WsServerMessage(
                    clusterMessage.getMessageType(),
                    clusterMessage.getContent()
            );

            // 按 clientId 精准推送
            if (clusterMessage.getTargetClientId() != null) {
                GmdWebSocketSession session = GmdWebSocketSession.getSessionByClientId(clusterMessage.getTargetClientId());
                if (session != null && session.writeable()) {
                    // 租户过滤：仅当消息无租户限制（超管）或session租户匹配时投递
                    if (clusterMessage.getTenantId() == null || tenantMatches(session, clusterMessage.getTenantId())) {
                        GmdWebSocketSessionManager.sendBySession(session, wsMessage);
                        log.info("集群消息转发成功：targetClientId={}", clusterMessage.getTargetClientId());
                    }
                } else {
                    log.debug("目标客户端不在本节点：targetClientId={}", clusterMessage.getTargetClientId());
                }
            }
            // 按 userId 推送
            else if (clusterMessage.getUserId() != null) {
                List<GmdWebSocketSession> sessions = GmdWebSocketSession.getSessionByAccountId(clusterMessage.getUserId());
                int count = 0;
                for (GmdWebSocketSession session : sessions) {
                    if (session != null && session.writeable()) {
                        // 租户过滤
                        if (clusterMessage.getTenantId() == null || tenantMatches(session, clusterMessage.getTenantId())) {
                            GmdWebSocketSessionManager.sendBySession(session, wsMessage);
                            count++;
                        }
                    }
                }
                log.info("集群消息转发成功：userId={}, sessionCount={}", clusterMessage.getUserId(), count);
            }
            // 全广播（带租户过滤）
            else {
                Long tenantId = clusterMessage.getTenantId();
                if (tenantId != null) {
                    sendToTenant(tenantId, wsMessage, clusterMessage.getExcludeClientId());
                } else {
                    // 无租户限制（超管操作），全广播
                    if (clusterMessage.getExcludeClientId() != null) {
                        GmdWebSocketSessionManager.sendToOther(clusterMessage.getExcludeClientId(), wsMessage);
                    } else {
                        GmdWebSocketSessionManager.sendToAll(wsMessage);
                    }
                }
                log.info("集群广播消息转发成功：messageType={}", clusterMessage.getMessageType());
            }
        } catch (Exception e) {
            log.error("处理集群消息失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 检查 session 是否属于指定租户
     */
    private boolean tenantMatches(GmdWebSocketSession session, Long tenantId) {
        if (session.getUser() == null) return false;
        return tenantId.equals(session.getUser().getTenantId());
    }

    /**
     * 向指定租户的所有 session 广播消息
     */
    private void sendToTenant(Long tenantId, WsServerMessage content, String excludeClientId) {
        GmdWebSocketSession.getSessionCache().values().stream()
                .filter(GmdWebSocketSession::writeable)
                .filter(session -> tenantMatches(session, tenantId))
                .filter(session -> excludeClientId == null || !excludeClientId.equals(session.getClientId()))
                .forEach(session -> GmdWebSocketSessionManager.sendBySession(session, content));
    }
}
