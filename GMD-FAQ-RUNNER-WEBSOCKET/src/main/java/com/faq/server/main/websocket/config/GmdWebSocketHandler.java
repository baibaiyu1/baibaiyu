package com.gmd.faq.server.main.websocket.config;

import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.websocket.handler.ClientMessageHandlerAdapter;
import com.gmd.faq.server.main.websocket.handler.client.ClientMessageHandler;
import com.gmd.faq.server.main.websocket.model.WsClientMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 描述：WebSocket 处理器
 *
 **/
@Slf4j
@Component
public class GmdWebSocketHandler extends TextWebSocketHandler {

    /**
     * 连接建立后
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 将 WebSocketSession 包装成 GmdWebSocketSession 并进行缓存
        GmdWebSocketSession gmdSession = GmdWebSocketSession.from(session);

        // 从 attributes 中获取认证信息并设置
        UserDetail userDetail = (UserDetail)session.getAttributes().get(SysConst.USER_DETAIL);

        if (userDetail != null) {
            // 设置用户信息
            gmdSession.setUser(userDetail);

            log.info("WebSocket 连接建立成功：userId={}, username={}",
                    userDetail.getId(),
                    userDetail.getUsername());
        } else {
            log.warn("WebSocket 连接建立但缺少用户信息：sessionId={}", session.getId());
        }
    }

    /**
     * 链接断开时
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        GmdWebSocketSession gmdSession = GmdWebSocketSession.from(session);

        // 从本地缓存移除
        GmdWebSocketSessionManager.remove(gmdSession);

        log.info("WebSocket 连接关闭：userId={}, clientId={}, status={}",
            gmdSession.getUser() != null ? gmdSession.getUser().getId() : "null",
            gmdSession.getClientId(), status);
    }

    /**
     * 处理客户端的消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            // 刷新会话 TTL
            GmdWebSocketSession gmdSession = GmdWebSocketSession.from(session);

            // 将消息负载转成对象
            WsClientMessage messageWrap = JsonUtils.parse(message.getPayload(), WsClientMessage.class);
            // 获取消息处理器
            ClientMessageHandler handler = ClientMessageHandlerAdapter.getHandler(messageWrap.getType());
            if (handler != null) {
                handler.handle(gmdSession, messageWrap);
            }

        } catch (Exception e) {
            log.error("处理 WebSocket 消息失败：{}", e.getMessage(), e);
        }
    }
}
