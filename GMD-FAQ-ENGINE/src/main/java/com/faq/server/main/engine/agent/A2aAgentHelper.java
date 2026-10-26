package com.gmd.faq.server.main.engine.agent;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.gmd.faq.server.main.a2a.config.NacosAgentConfig;
import com.gmd.faq.server.main.a2a.config.WellKnownAgentConfig;
import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.hook.HooksFactory;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * 描述：A2a智能体Helper
 *
 **/
@Component
@RequiredArgsConstructor
public class A2aAgentHelper {
    private final HooksFactory hooksFactory;
    private final AgentA2aService agentA2aService;

    /**
     * 获取 A2aAgent
     * @param definition agent 定义
     */
    public A2aAgent getA2aAgent(AgentDefinition definition) {
        AgentA2A agentA2A = agentA2aService.getA2aConfigByAgentId(definition.getId());
        if (agentA2A == null)
            throw new RuntimeException("Agent A2A config not found, agentId: " + definition.getId());

        return switch (agentA2A.getA2aType()) {
            case NACOS -> {
                NacosAgentConfig config = JsonUtils.parse(agentA2A.getA2aConfig().toString(), NacosAgentConfig.class);
                yield createNacosA2aAgentBuilder(config, definition).build();
            }
            case WELLKNOWN -> {
                WellKnownAgentConfig config = JsonUtils.parse(agentA2A.getA2aConfig().toString(), WellKnownAgentConfig.class);
                yield createWellknownA2aAgentBuilder(config, definition).build();
            }
        };
    }

    /**
     * 获取 A2aAgent Builder
     * @param definition agent 定义
     */
    public A2aAgent.Builder getA2aAgentBuilder(AgentDefinition definition) {
        AgentA2A agentA2A = agentA2aService.getA2aConfigByAgentId(definition.getId());
        if (agentA2A == null)
            throw new RuntimeException("Agent A2A config not found, agentId: " + definition.getId());

        return switch (agentA2A.getA2aType()) {
            case NACOS -> {
                NacosAgentConfig config = JsonUtils.parse(agentA2A.getA2aConfig().toString(), NacosAgentConfig.class);
                yield createNacosA2aAgentBuilder(config, definition);
            }
            case WELLKNOWN -> {
                WellKnownAgentConfig config = JsonUtils.parse(agentA2A.getA2aConfig().toString(), WellKnownAgentConfig.class);
                yield createWellknownA2aAgentBuilder(config, definition);
            }
        };
    }

    /**
     * 获取 A2aAgent.Builder
     * @param config WellKnown Agent 配置
     * @param definition 智能体定义
     */
    public A2aAgent.Builder createWellknownA2aAgentBuilder(WellKnownAgentConfig config, AgentDefinition definition) {
        A2aAgent.Builder builder = A2aAgent.builder()
                .name(config.getAgentName())
                .agentCardResolver(WellKnownAgentCardResolver.builder()
                        .baseUrl(config.getBaseUrl())
                        .relativeCardPath(config.getRelativeCardPath())
                        .authHeaders(config.getRealAuthHeaders())
                        .build());

        return fillA2aAgentExpand(definition, builder);
    }

    /**
     * 获取 A2aAgent.Builder
     * @param config Nacos Agent 配置
     * @param definition 智能体定义
     */
    public A2aAgent.Builder createNacosA2aAgentBuilder(NacosAgentConfig config, AgentDefinition definition) {

        Properties nacosProperties = config.getNacosProperties();

        AiService aiService;
        try {
            aiService = AiFactory.createAiService(nacosProperties);
        } catch (NacosException e) {
            throw new RuntimeException("Nacos AI service initialization failed", e);
        }

        NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(aiService);
        A2aAgent.Builder builder = A2aAgent.builder()
                .name(config.getAgentName())
                .agentCardResolver(nacosAgentCardResolver);

        return fillA2aAgentExpand(definition, builder);
    }

    private A2aAgent.Builder fillA2aAgentExpand(AgentDefinition definition, A2aAgent.Builder builder) {
        // 使用可变列表，避免 getHooks 返回 List.of() 时 add 抛 UnsupportedOperationException
        List<Hook> hooks = hooksFactory.getHooks(definition);
        if (hooks != null && !hooks.isEmpty()) {
            builder.hooks(hooks);
        }

        // 配置记忆
        if (Boolean.TRUE.equals(definition.getEnableMemory())) {
            builder.memory(new InMemoryMemory());
        }
        AgentContext.getIfExists().ifPresent(context -> context.setAgentDefinition(definition));

        return builder;
    }
}
