package com.gmd.faq.server.main.websocket.handler.client;

import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.handler.ClientMessageHandlerAdapter;
import com.gmd.faq.server.main.websocket.model.WsClientMessage;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 描述：客户端 websocket 消息处理器
 *
 **/
public interface ClientMessageHandler extends SmartInitializingSingleton {
    /**
     * 可以处理的消息类型
     */
    WsMessageType messageType();

    /**
     * 处理消息的执行方法
     *
     * @param session GmdWebSocketSession
     * @param msg     消息
     */
    void handle(GmdWebSocketSession session, WsClientMessage msg);

    /**
     * 注册实现类的Handler
     */
    ClientMessageHandler register();


    @Override
    default void afterSingletonsInstantiated() {
        ClientMessageHandlerAdapter.register(register());
    }
}
