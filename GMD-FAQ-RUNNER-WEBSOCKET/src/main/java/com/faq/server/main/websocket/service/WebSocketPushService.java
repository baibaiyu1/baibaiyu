package com.gmd.faq.server.main.websocket.service;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.websocket.cluster.ClusterMessage;
import com.gmd.faq.server.main.websocket.cluster.RedisSessionManager;
import com.gmd.faq.server.main.websocket.config.GmdWebSocketSessionManager;
import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 描述：WebSocket 消息推送服务 - 支持集群环境
 *
 **/
@Slf4j
@Service
public class WebSocketPushService {

    private final RedisSessionManager redisSessionManager;

    public WebSocketPushService(RedisSessionManager redisSessionManager) {
        this.redisSessionManager = redisSessionManager;
    }

    /**
     * 推送消息给指定客户端（本地）
     *
     * @param clientId 客户端 ID
     * @param message  消息内容
     */
    public void pushToClient(String clientId, WsServerMessage message) {
        GmdWebSocketSession session = GmdWebSocketSession.getSessionByClientId(clientId);
        if (session != null && session.writeable()) {
            GmdWebSocketSessionManager.sendBySession(session, message);
            log.info("推送消息到本地客户端：clientId={}, messageType={}", clientId, message.getType());
        } else {
            log.warn("目标客户端不在本节点：clientId={}", clientId);
        }
    }

    /**
     * 推送消息给指定用户的所有客户端（本地）
     *
     * @param userId  用户 ID
     * @param message 消息内容
     */
    public void pushToUser(String userId, WsServerMessage message) {
        List<GmdWebSocketSession> sessions = GmdWebSocketSession.getSessionByAccountId(userId);
        for (GmdWebSocketSession session : sessions) {
            if (session != null && session.writeable()) {
                GmdWebSocketSessionManager.sendBySession(session, message);
            }
        }
        log.info("推送消息给用户：userId={}, sessionCount={}", userId, sessions.size());
    }

    /**
     * 广播消息给所有在线客户端（本地）
     *
     * @param message 消息内容
     */
    public void broadcast(WsServerMessage message) {
        GmdWebSocketSessionManager.sendToAll(message);
        log.info("广播消息：messageType={}", message.getType());
    }

    /**
     * 推送消息给指定客户端（集群）
     * 通过 Redis Pub/Sub 广播，由持有该客户端会话的节点负责投递
     * 携带 sourceNodeId，订阅方收到后若匹配本节点则跳过，避免重复推送
     *
     * @param clientId 客户端 ID
     * @param message  消息内容
     */
    public void pushToClientCluster(String clientId, WsServerMessage message) {
        try {
            ClusterMessage clusterMsg = ClusterMessage.builder()
                    .targetClientId(clientId)
                    .messageType(message.getType())
                    .content(message.getContent())
                    .tenantId(TenantUtils.getCurrentTenantId())
                    .sourceNodeId(SysConst.CURRENT_NODE_ID)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String messageStr = JsonUtils.toJsonStr(clusterMsg);
            redisSessionManager.broadcastMessage("gmd-faq:ws:cluster:message", messageStr);
            log.info("通过集群广播推送消息：clientId={}, sourceNodeId={}", clientId, SysConst.CURRENT_NODE_ID);
        } catch (Exception e) {
            log.error("集群推送消息失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 推送消息给指定用户的所有客户端（集群）
     * 通过 Redis Pub/Sub 广播到所有节点（含本节点），携带 sourceNodeId 供订阅方去重
     *
     * @param userId  用户 ID
     * @param message 消息内容
     */
    public void pushToUserCluster(String userId, WsServerMessage message) {
        try {
            ClusterMessage clusterMsg = ClusterMessage.builder()
                    .userId(userId)
                    .messageType(message.getType())
                    .content(message.getContent())
                    .tenantId(TenantUtils.getCurrentTenantId())
                    .sourceNodeId(SysConst.CURRENT_NODE_ID)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String messageStr = JsonUtils.toJsonStr(clusterMsg);
            redisSessionManager.broadcastMessage("gmd-faq:ws:cluster:user:" + userId, messageStr);
            log.info("通过集群广播推送消息给用户：userId={}, sourceNodeId={}", userId, SysConst.CURRENT_NODE_ID);
        } catch (Exception e) {
            log.error("集群推送消息失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 推送消息给指定用户编码的所有客户端（集群）
     * 通过 Redis Pub/Sub 广播到所有节点（含本节点），携带 sourceNodeId 供订阅方去重
     *
     * @param userCode 用户编码
     * @param message  消息内容
     */
    public void pushToUserCodeCluster(String userCode, WsServerMessage message) {
        try {
            ClusterMessage clusterMsg = ClusterMessage.builder()
                    .userCode(userCode)
                    .messageType(message.getType())
                    .content(message.getContent())
                    .tenantId(TenantUtils.getCurrentTenantId())
                    .sourceNodeId(SysConst.CURRENT_NODE_ID)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String messageStr = JsonUtils.toJsonStr(clusterMsg);
            redisSessionManager.broadcastMessage("gmd-faq:ws:cluster:usercode:" + userCode, messageStr);
            log.info("通过集群广播推送消息给用户编码：userCode={}, sourceNodeId={}", userCode, SysConst.CURRENT_NODE_ID);
        } catch (Exception e) {
            log.error("集群推送消息失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 广播消息到所有节点
     * 通过 Redis Pub/Sub 广播到所有节点（含本节点），携带 sourceNodeId 供订阅方去重
     *
     * @param message 消息内容
     */
    public void broadcastCluster(WsServerMessage message) {
        try {
            ClusterMessage clusterMsg = ClusterMessage.builder()
                    .messageType(message.getType())
                    .content(message.getContent())
                    .tenantId(TenantUtils.getCurrentTenantId())
                    .sourceNodeId(SysConst.CURRENT_NODE_ID)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String messageStr = JsonUtils.toJsonStr(clusterMsg);
            redisSessionManager.broadcastMessage("gmd-faq:ws:cluster:broadcast", messageStr);
            log.info("通过集群广播消息：messageType={}, sourceNodeId={}", message.getType(), SysConst.CURRENT_NODE_ID);
        } catch (Exception e) {
            log.error("集群广播消息失败：{}", e.getMessage(), e);
        }
    }
}
