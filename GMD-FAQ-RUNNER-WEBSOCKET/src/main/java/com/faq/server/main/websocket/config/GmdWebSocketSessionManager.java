package com.gmd.faq.server.main.websocket.config;

import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.handler.ServiceMessageHandlerAdapter;
import com.gmd.faq.server.main.websocket.handler.server.ServerMessageHandler;
import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 描述：session 管理器
 *
 **/
public class GmdWebSocketSessionManager {

    private static final int CHECK_INTERVAL = 20;

    private static final int KEEPALIVE_TIMEOUT = 60 * 1000;

    private static final Logger logger = LoggerFactory.getLogger(GmdWebSocketSessionManager.class);

    private static final ScheduledExecutorService SCHEDULER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "gmd-websocket-clean-task");
                t.setDaemon(true); // 设置为守护线程，不阻塞JVM关闭
                return t;
            });

    static {
        SCHEDULER.scheduleAtFixedRate(
                GmdWebSocketSessionManager::checkSession,
                CHECK_INTERVAL,
                CHECK_INTERVAL,
                TimeUnit.SECONDS
        );

        // 注册JVM关闭钩子，优雅停止调度器
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SCHEDULER.shutdown();
            try {
                if (!SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                    SCHEDULER.shutdownNow();
                }
            } catch (InterruptedException e) {
                SCHEDULER.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    /**
     * 根据clientId获取session
     *
     * @param clientId 客户端
     */
    public static GmdWebSocketSession getSessionByClientId(String clientId) {
        return GmdWebSocketSession.getSessionByClientId(clientId);
    }

    /**
     * 根据 account 获取 session 集合
     *
     * @param userId 用户 ID
     */
    public static List<GmdWebSocketSession> getSessionByAccountId(String userId) {
        return GmdWebSocketSession.getSessionByAccountId(userId);
    }

    /**
     * 移除并关闭指定的session
     *
     * @param session WebSocketSession包装类GmdWebSocketSession
     */
    public static void remove(GmdWebSocketSession session) {
        if (session!= null) {
            session.close();
        }
    }

    /**
     * 发送消息给所有连接建立的session
     *
     * @param content 消息内容
     */
    public static void sendToAll(WsServerMessage content) {
        sendToOther(new ArrayList<>(), content);
    }

    /**
     * 发送消息给连接建立的session，排除指定的客户端session
     *
     * @param excludeClientId 被排除的客户端session
     * @param content         消息内容
     */
    public static void sendToOther(String excludeClientId, WsServerMessage content) {
        sendToOther(new ArrayList<String>(){{add(excludeClientId);}}, content);
    }

    /**
     * 发送消息给连接建立的session，排除指定的客户端session
     *
     * @param excludeClientIds 被排除的客户端session集合
     * @param content          消息内容
     */
    public static void sendToOther(List<String> excludeClientIds, WsServerMessage content) {
        GmdWebSocketSession.getSessionCache().entrySet()
                .stream()
                .filter(entry -> !excludeClientIds.contains(entry.getKey()))
                .forEach(entry -> sendBySession(entry.getValue(), content));
    }

    /**
     * 给指定的客户单发送消息
     *
     * @param targetClientId 目标客户端
     * @param content        消息内容
     */
    public static void sendToTarget(String targetClientId, WsServerMessage content) {
        GmdWebSocketSession session = GmdWebSocketSession.getSessionByClientId(targetClientId);
        sendBySession(session, content);
    }

    /**
     * 给指定的session发送消息
     *
     * @param session 目标session
     * @param content 消息内容
     */
    public static void sendBySession(GmdWebSocketSession session, WsServerMessage content) {
        try {
            if (session != null) {
                synchronized (session.getClientId()) {
                    session.getWebSocketSession().sendMessage(new TextMessage(content.toJson()));
                }
            }
        } catch (Exception e) {
            session.close();
            logger.warn("GmdWebSocketSessionManager发送WebSocket消息失败: {}", e.getMessage());
        }
    }

    /**
     * 心跳检测，检测session的存活
     */
    private static void checkSession() {
        try {
            long activateTime = System.currentTimeMillis() - KEEPALIVE_TIMEOUT;
            GmdWebSocketSession.getSessionCache().entrySet().stream()
                    // 给所有的session发送检测存活的消息消息
                    .peek(it -> {
                        // 获取服务器消息处理器
                        ServerMessageHandler handler = ServiceMessageHandlerAdapter.getHandler(WsMessageType.PING);
                        if (handler != null) {
                            handler.handle(it.getValue(), WsServerMessage.build("PING", "PING"));
                        }
                    })
                    // 过滤出在规定时间内没有得到响应的客户端session，这些session被认定为不健康
                    .filter(it -> it.getValue().getActivateTime() < activateTime)
                    .toList()
                    // 移除不健康的session
                    .forEach(entry -> {
                        GmdWebSocketSession session = entry.getValue();
                        session.close();
                    });
        } catch (Exception ignored) {
        }
    }

}
