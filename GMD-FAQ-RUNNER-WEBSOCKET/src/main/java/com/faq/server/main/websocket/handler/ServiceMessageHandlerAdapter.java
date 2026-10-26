package com.gmd.faq.server.main.websocket.handler;

import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.handler.server.ServerMessageHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：
 *
 **/
public class ServiceMessageHandlerAdapter {
    private static final Map<WsMessageType, ServerMessageHandler> CACHED = new ConcurrentHashMap<>();


    public static void register(ServerMessageHandler handler) {
        CACHED.put(handler.messageType(), handler);
    }

    public static ServerMessageHandler getHandler(WsMessageType type) {
        return CACHED.get(type);
    }
}
