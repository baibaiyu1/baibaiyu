package com.gmd.faq.server.main.engine.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.memory.LongTermMemoryBuilder;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 百炼长期记忆构建器
 * <p>
 * 构建 BailianLongTermMemory 实例，基于阿里云百炼的记忆库服务。
 * 用户标识使用服务端上下文中的租户 ID 与账号 ID。
 * 配置项：
 * - apiKey: 阿里云百炼 API 密钥
 * - memoryLibraryId: 记忆库 ID
 * - projectId: 项目 ID
 * - topK: 检索 topK
 * - scoreThreshold: 分数阈值
 * - memoryMode: 记忆控制模式
 *
 */
@Component
public class BailianLongTermMemoryBuilder implements LongTermMemoryBuilder {

    private static final Logger log = LoggerFactory.getLogger(BailianLongTermMemoryBuilder.class);

    @Override
    public LongTermMemory build(AgentDefinition definition, JsonNode config, AgentContext context) {
        log.info("长期记忆[百炼]: 创建 BailianLongTermMemory, agentId={}, agentCode={}",
                definition.getId(), definition.getAgentCode());

        BailianLongTermMemory.Builder builder = BailianLongTermMemory.builder();

        // userId: 从 AgentContext 获取登录用户信息
        String userId = resolveUserId(context);
        builder.userId(userId);

        // apiKey: 阿里云百炼 API 密钥
        String apiKey = JsonUtils.getStringValue(config, "apiKey", "");
        if (!apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }

        // memoryLibraryId: 记忆库 ID
        String memoryLibraryId = JsonUtils.getStringValue(config, "memoryLibraryId", "");
        if (!memoryLibraryId.isEmpty()) {
            builder.memoryLibraryId(memoryLibraryId);
        }

        // projectId: 项目 ID
        String projectId = JsonUtils.getStringValue(config, "projectId", "");
        if (!projectId.isEmpty()) {
            builder.projectId(projectId);
        }

        // topK: 检索 topK
        int topK = JsonUtils.getIntValue(config, "topK", 5);
        builder.topK(topK);

        // minScore: 最低匹配分数
        double minScore = JsonUtils.getDoubleValue(config, "minScore", 0.5);
        builder.minScore(minScore);

        return builder.build();
    }
}
