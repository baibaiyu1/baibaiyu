package com.gmd.faq.server.main.websocket.handler;

import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.handler.client.ClientMessageHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：websocket 消息处理器适配器
 *
 **/
public class ClientMessageHandlerAdapter {
    private static final Map<WsMessageType, ClientMessageHandler> CACHED = new ConcurrentHashMap<>();


    public static void register(ClientMessageHandler handler) {
        CACHED.put(handler.messageType(), handler);
    }

    public static ClientMessageHandler getHandler(WsMessageType type) {
        return CACHED.get(type);
    }
}
