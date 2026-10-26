package com.gmd.faq.server.main.engine.model;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.OkHttpTransport;

import java.time.Duration;

/**
 * 描述：HttpTransport 生成器
 *
 **/
public class HttpTransportHelper {
    public static final int DEFAULT_CHAT_READ_TIMEOUT_SECONDS = 600;

    public static HttpTransport createJdkHttpTransport() {
        return JdkHttpTransport.builder()
                .config(getHttpTransportConfig(10, 60))
                .build();
    }

    public static HttpTransport createOkHttpTransport() {
        return createOkHttpTransport(10, DEFAULT_CHAT_READ_TIMEOUT_SECONDS);
    }

    public static HttpTransport createOkHttpTransport(int connectTimeout, int readTimeout) {
        return OkHttpTransport.builder()
                .config(getHttpTransportConfig(connectTimeout, readTimeout))
                .build();
    }

    private static HttpTransportConfig getHttpTransportConfig(int connectTimeout, int readTimeout) {
        return HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .readTimeout(Duration.ofSeconds(readTimeout))
                .ignoreSsl(true)
                .build();
    }
}
