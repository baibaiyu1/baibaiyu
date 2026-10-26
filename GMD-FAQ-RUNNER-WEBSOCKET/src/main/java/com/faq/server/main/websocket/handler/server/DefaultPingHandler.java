package com.gmd.faq.server.main.websocket.handler.server;

import com.gmd.faq.server.main.websocket.config.GmdWebSocketSessionManager;
import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import org.springframework.stereotype.Service;

/**
 * 描述：服务端向客户端发送PING类型的消息时的处理器。
 * 维护session健康状态
 *
 **/
@Service
public class DefaultPingHandler implements ServerMessageHandler {
    @Override
    public WsMessageType messageType() {
        return WsMessageType.PING;
    }

    @Override
    public void handle(GmdWebSocketSession session, WsServerMessage msg) {
        if (msg == null) {
            return;
        }
        // 发送心跳给客户端
        GmdWebSocketSessionManager.sendBySession(session, msg);
    }

    @Override
    public ServerMessageHandler register() {
        return this;
    }
}
