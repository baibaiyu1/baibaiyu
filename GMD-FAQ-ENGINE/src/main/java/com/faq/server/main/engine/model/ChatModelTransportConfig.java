package com.gmd.faq.server.main.engine.model;

import io.agentscope.core.model.transport.HttpTransport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gmd.faq.model")
public class ChatModelTransportConfig {

    private int chatReadTimeoutSeconds = HttpTransportHelper.DEFAULT_CHAT_READ_TIMEOUT_SECONDS;

    public int getChatReadTimeoutSeconds() {
        return chatReadTimeoutSeconds;
    }

    public void setChatReadTimeoutSeconds(int chatReadTimeoutSeconds) {
        if (chatReadTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("chatReadTimeoutSeconds must be positive");
        }
        this.chatReadTimeoutSeconds = chatReadTimeoutSeconds;
    }

    public HttpTransport createChatTransport() {
        return HttpTransportHelper.createOkHttpTransport(10, chatReadTimeoutSeconds);
    }
}
