package com.gmd.faq.server.main.engine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;

/**
 * 长期记忆构建器策略接口
 * <p>
 * 每种长期记忆类型（Mem0、ReMe、百炼等）实现此接口，
 * 通过策略模式实现可插拔的长期记忆构建。
 *
 */
public interface LongTermMemoryBuilder {

    /**
     * 根据 AgentDefinition 构建 LongTermMemory 实例
     *
     * @param definition Agent 定义
     * @param config     长期记忆配置（JSON格式，扁平结构）
     * @param context    Agent 上下文（用于获取用户信息等）
     * @return LongTermMemory 实例
     */
    LongTermMemory build(AgentDefinition definition, JsonNode config, AgentContext context);

    /**
     * 从 AgentContext 中解析用户标识
     * 优先级：nickname > username > id
     */
    default String resolveUserId(AgentContext context) {
        if (context == null) {
            return "default_user";
        }
        AccountVO userInfo = context.getUserInfo();
        if (userInfo == null) {
            return "default_user";
        }
        if (userInfo.getNickname() != null && !userInfo.getNickname().isEmpty()) {
            return userInfo.getNickname();
        }
        if (userInfo.getUsername() != null && !userInfo.getUsername().isEmpty()) {
            return userInfo.getUsername();
        }
        return String.valueOf(userInfo.getId());
    }

    default LongTermMemoryMode getMemoryMode(JsonNode config) {
        if (config == null) {
            return LongTermMemoryMode.BOTH;
        }

        String mode = config.has("memoryMode") ? config.get("memoryMode").asText("BOTH") : "BOTH";
        try {
            return LongTermMemoryMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return LongTermMemoryMode.BOTH;
        }
    }
}
