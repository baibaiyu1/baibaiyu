package com.gmd.faq.server.main.engine.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.memory.LongTermMemoryBuilder;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ReMe 长期记忆构建器
 * <p>
 * 构建 ReMeLongTermMemory 实例，基于阿里通义千问的记忆服务。
 * 用户标识从 AgentContext 中自动获取（优先使用 nickname，其次 username，最后 id）。
 * 配置项：
 * - apiBaseUrl: ReMe 服务地址
 * - timeout: 请求超时时间（秒）
 * - memoryMode: 记忆控制模式
 *
 */
@Component
public class ReMeLongTermMemoryBuilder implements LongTermMemoryBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReMeLongTermMemoryBuilder.class);

    @Override
    public LongTermMemory build(AgentDefinition definition, JsonNode config, AgentContext context) {
        log.info("长期记忆[ReMe]: 创建 ReMeLongTermMemory, agentId={}, agentCode={}",
                definition.getId(), definition.getAgentCode());

        ReMeLongTermMemory.Builder builder = ReMeLongTermMemory.builder();

        // userId: 从 AgentContext 获取登录用户信息
        String userId = resolveUserId(context);
        builder.userId(userId);

        // apiBaseUrl: ReMe 服务地址
        String apiBaseUrl = JsonUtils.getStringValue(config, "apiBaseUrl", "https://api.reme.ai");
        builder.apiBaseUrl(apiBaseUrl);

        // timeout: 请求超时时间（秒）
        long timeoutSeconds = JsonUtils.getLongValue(config, "timeout", 30L);
        builder.timeout(Duration.ofSeconds(timeoutSeconds));

        return builder.build();
    }
}
