package com.gmd.faq.server.main.websocket.handler.client;

import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.model.WsClientMessage;
import org.springframework.stereotype.Service;

/**
 * 描述：客户端向服务端发送PONG类型的消息时的处理器。
 * 维护session健康状态
 *
 **/
@Service
public class DefaultPongHandler implements ClientMessageHandler {
    @Override
    public WsMessageType messageType() {
        return WsMessageType.PONG;
    }

    @Override
    public void handle(GmdWebSocketSession session, WsClientMessage msg) {
        if (msg == null) {
            return;
        }
        session.setActivateTime(System.currentTimeMillis());
    }

    @Override
    public ClientMessageHandler register() {
        return this;
    }
}
