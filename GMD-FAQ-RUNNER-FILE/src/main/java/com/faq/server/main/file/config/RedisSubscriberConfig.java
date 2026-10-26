package com.gmd.faq.server.main.file.config;

import com.gmd.faq.server.main.file.sync.SkillFileSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 描述：Redis 消息订阅配置
 * 自行实现消息监听容器，注册 SkillFileSubscriber 到技能同步频道
 *
 **/
@Configuration
public class RedisSubscriberConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisSubscriberConfig.class);

    /** 技能文件同步 Redis 频道
     * 必须与 common 模块 RedisChannelTopic.SKILL_FILE_SYNC_CHANNEL 保持一致
     */
    public static final String SKILL_FILE_SYNC_CHANNEL = "gmd-faq:skill:file:sync";

    /**
     * Redis 消息监听容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            SkillFileSubscriber skillFileSubscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisListenerExecutor());

        container.addMessageListener((message, pattern) -> {
            try {
                String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                skillFileSubscriber.onMessage(channel, body);
            } catch (Exception e) {
                log.error("处理 Redis 消息异常", e);
            }
        }, new ChannelTopic(SKILL_FILE_SYNC_CHANNEL));

        return container;
    }

    /**
     * Redis 消息监听专用线程池
     */
    @Bean
    public ThreadPoolTaskExecutor redisListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("file-redis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
