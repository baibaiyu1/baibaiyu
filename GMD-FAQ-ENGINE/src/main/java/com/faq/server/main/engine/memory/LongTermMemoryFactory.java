package com.gmd.faq.server.main.engine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.LongTermMemoryConfig;
import com.gmd.faq.server.main.common.enums.LongTermMemoryType;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.memory.longterm.BailianLongTermMemoryBuilder;
import com.gmd.faq.server.main.engine.memory.longterm.Mem0LongTermMemoryBuilder;
import com.gmd.faq.server.main.engine.memory.longterm.ReMeLongTermMemoryBuilder;
import com.gmd.faq.server.main.longterm.service.AgentLongTermMemoryService;
import com.gmd.faq.server.main.longterm.service.LongTermMemoryConfigService;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 长期记忆工厂
 * <p>
 * 使用策略模式管理多种长期记忆实现（Mem0、ReMe、百炼等）。
 * 通过 AgentLongTermMemory 关联表和 LongTermMemoryConfig 表获取配置，
 * 根据配置中的 memoryType 动态选择对应的 Builder 构建 LongTermMemory 实例。
 * 用户标识从 AgentContext 中自动获取，无需在配置中指定。
 *
 */
@Component
public class LongTermMemoryFactory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryFactory.class);

    private final Map<LongTermMemoryType, LongTermMemoryBuilder> builderMap;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final LongTermMemoryConfigService longTermMemoryConfigService;

    public LongTermMemoryFactory(List<LongTermMemoryBuilder> builders,
                                  AgentLongTermMemoryService agentLongTermMemoryService,
                                  LongTermMemoryConfigService longTermMemoryConfigService) {
        this.builderMap = builders.stream()
                .collect(Collectors.toMap(
                        this::resolveBuilderType,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
        this.agentLongTermMemoryService = agentLongTermMemoryService;
        this.longTermMemoryConfigService = longTermMemoryConfigService;
        log.info("长期记忆工厂初始化完成，已注册 {} 种记忆类型: {}", builderMap.size(), builderMap.keySet());
    }

    /**
     * 根据 AgentDefinition 创建 LongTermMemory
     *
     * @param definition Agent 定义
     * @return LongTermMemory 实例，如果未配置或配置无效则返回 null
     */
    public LongTermMemory createLongTermMemory(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }

        // 查询关联的配置ID
        Long configId = agentLongTermMemoryService.getConfigIdByAgentId(definition.getId());
        if (configId == null) {
            return null;
        }

        // 获取配置实体
        LongTermMemoryConfig configEntity = longTermMemoryConfigService.getById(configId);
        if (configEntity == null || !Boolean.TRUE.equals(configEntity.getEnabled())) {
            log.warn("长期记忆配置未启用或不存在，agentId={}", definition.getId());
            return null;
        }

        LongTermMemoryType memoryType = configEntity.getMemoryType();
        if (memoryType == null) {
            log.warn("长期记忆类型为空，agentId={}", definition.getId());
            return null;
        }

        LongTermMemoryBuilder builder = builderMap.get(memoryType);
        if (builder == null) {
            log.warn("未找到 {} 类型的长期记忆构建器，agentId={}", memoryType, definition.getId());
            return null;
        }

        // 扁平配置：直接使用 config 字段，无需 resolveTypeConfig
        JsonNode config = configEntity.getConfig();

        // 从 AgentContext 获取用户信息
        AgentContext context = AgentContext.getIfExists().orElse(null);

        return builder.build(definition, config, context);
    }

    /**
     * 获取 LongTermMemoryMode
     *
     * @param definition Agent 定义
     * @return LongTermMemoryMode，如果未配置则返回 null
     */
    public LongTermMemoryMode getMemoryMode(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }

        Long configId = agentLongTermMemoryService.getConfigIdByAgentId(definition.getId());
        if (configId == null) {
            return null;
        }

        LongTermMemoryConfig configEntity = longTermMemoryConfigService.getById(configId);
        if (configEntity == null || !Boolean.TRUE.equals(configEntity.getEnabled())) {
            return null;
        }

        LongTermMemoryType memoryType = configEntity.getMemoryType();
        if (memoryType == null) {
            return null;
        }

        LongTermMemoryBuilder builder = builderMap.get(memoryType);
        if (builder == null) {
            return null;
        }

        return builder.getMemoryMode(configEntity.getConfig());
    }

    /**
     * 根据 Builder 实例解析对应的记忆类型
     */
    private LongTermMemoryType resolveBuilderType(LongTermMemoryBuilder builder) {
        if (builder instanceof Mem0LongTermMemoryBuilder) {
            return LongTermMemoryType.MEM0;
        } else if (builder instanceof ReMeLongTermMemoryBuilder) {
            return LongTermMemoryType.REME;
        } else if (builder instanceof BailianLongTermMemoryBuilder) {
            return LongTermMemoryType.BAILIAN;
        }
        throw new IllegalArgumentException("未知的 LongTermMemoryBuilder 类型: " + builder.getClass().getName());
    }
}
