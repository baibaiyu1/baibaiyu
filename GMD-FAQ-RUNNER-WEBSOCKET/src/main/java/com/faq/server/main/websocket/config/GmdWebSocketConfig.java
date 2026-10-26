package com.gmd.faq.server.main.websocket.config;

import com.gmd.faq.server.main.websocket.interceptor.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 描述：WebSocketConfig
 * 配置 WebSocket 的 endpoint
 *
 **/
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class GmdWebSocketConfig implements WebSocketConfigurer {
    private final GmdWebSocketHandler gmdWebSocketHandler;
    private final WebSocketAuthInterceptor authInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 添加握手拦截器进行认证
        registry.addHandler(gmdWebSocketHandler, "/ws/gmd-faq")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");
    }
}
