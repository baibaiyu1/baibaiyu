package com.gmd.faq.server.main.heartbeat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 描述：心跳模块自动配置
 * 仅当 heartbeat.enabled=true 时生效，console 引入此模块不会自动开启心跳发送
 *
 **/
@Configuration
@ConditionalOnProperty(prefix = "heartbeat", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(HeartbeatProperties.class)
public class HeartbeatAutoConfiguration {

    @Bean
    public HeartbeatSender heartbeatSender(HeartbeatProperties properties) {
        return new HeartbeatSender(properties);
    }
}
