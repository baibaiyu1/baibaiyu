package com.gmd.faq.server.main.config;

import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * 线程会话管理器配置类
 *
 **/
@Configuration
public class ThreadSessionManagerConfig {
    /**
     * 配置线程会话管理器
     *
     * @return 线程会话管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public ThreadSessionManager threadSessionManager() {
        return new ThreadSessionManager(
               100, 30);
    }
}
