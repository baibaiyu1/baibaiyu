package com.gmd.faq.server.main.websocket.handler.server;

import com.gmd.faq.server.main.websocket.context.GmdWebSocketSession;
import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.websocket.handler.ServiceMessageHandlerAdapter;
import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 描述：服务端 websocket 消息处理器
 *
 **/
public interface ServerMessageHandler extends SmartInitializingSingleton  {
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
    void handle(GmdWebSocketSession session, WsServerMessage msg);

    /**
     * 注册实现类的Handler
     */
    ServerMessageHandler register();


    @Override
    default void afterSingletonsInstantiated() {
        ServiceMessageHandlerAdapter.register(register());
    }
}
