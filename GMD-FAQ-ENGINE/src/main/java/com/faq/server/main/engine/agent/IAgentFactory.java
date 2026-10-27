package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.agent.Agent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 描述：智能体工厂类
 *
 **/
@Component
@RequiredArgsConstructor
public class IAgentFactory {
    private final TenantService tenantService;
    private final A2aAgentHelper a2aAgentHelper;
    private final ReActAgentHelper reActAgentHelper;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final com.gmd.faq.server.main.agent.service.CodeExecutionConfigService codeExecutionConfigService;

    /**
     * 根据Agent定义ID获取Agent
     *
     * @param agentId Agent定义ID
     * @param tenantId 租户ID
     */
    public Agent getAgent(Long agentId, Long tenantId) {
        try {
            setTenantInfo(tenantId);
            AgentDefinition definition = agentDefinitionService.getById(agentId);
            validAgentDefinition(definition);

            return switch (definition.getAgentType()) {
                case CUSTOM -> getReActAgent(definition);
                case A2A -> getA2aAgent(definition);
                default -> throw new IllegalArgumentException("未知的智能体类型");
            };
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        } finally {
            TenantUtils.clear();
        }
    }

    /**
     * 根据Agent定义获取A2aAgent
     * @param definition Agent 定义
     */
    private A2aAgent getA2aAgent(AgentDefinition definition) {
        try {
            return a2aAgentHelper.getA2aAgent(definition);
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据Agent定义获取ReActAgent
     * @param definition Agent 定义
     */
    private ReActAgent getReActAgent(AgentDefinition definition) {
        try {
            ReActAgent reActAgent = reActAgentHelper.getReActAgent(definition);
            Long codeExecutionId = getCodeExecutionId(definition.getId());

            // AgentMetadataStore 中不存在 workspace_enabled 则表示没有开启工作空间能力
            CodeExecutionConfig execution = codeExecutionId == null ? null : codeExecutionConfigService.getById(codeExecutionId);
            if (execution != null && Boolean.TRUE.equals(execution.getEnabled())) {
                AgentMetadataStore.put(reActAgent.getAgentId(), "workspace_enabled", true);
            }
            return reActAgent;
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        }
    }

    private Long getCodeExecutionId(Long agentDefinitionId) {
        return agentCodeExecutionService.getCodeExecutionIdByAgentId(agentDefinitionId);
    }


    /**
     * 根据Agent定义ID获取Agent Builder
     *
     * @param agentId Agent定义ID
     * @param tenantId 租户ID
     */
    public AgentBuilderWrapper getAgentBuilder(Long agentId, Long tenantId) {
        try {
            setTenantInfo(tenantId);
            AgentDefinition definition = agentDefinitionService.getById(agentId);
            validAgentDefinition(definition);

            return switch (definition.getAgentType()) {
                case CUSTOM -> AgentBuilderWrapper.builder()
                        .definition(definition)
                        .reactAgentBuilder(getReActAgentBuilder(definition))
                        .build();
                case A2A -> AgentBuilderWrapper.builder()
                        .definition(definition)
                        .a2aAgentBuilder(getA2aAgentBuilder(definition))
                        .build();
                default -> throw new IllegalArgumentException("未知的智能体类型");
            };
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        } finally {
            TenantUtils.clear();
        }
    }

    /**
     * 根据Agent定义获取A2aAgent
     * @param definition Agent 定义
     */
    private A2aAgent.Builder getA2aAgentBuilder(AgentDefinition definition) {
        try {
            return a2aAgentHelper.getA2aAgentBuilder(definition);
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据Agent定义获取ReActAgent
     * @param definition Agent 定义
     */
    private ReActAgent.Builder getReActAgentBuilder(AgentDefinition definition) {
        try {
            return reActAgentHelper.getReactAgentBuilder(definition);
        } catch (Exception e) {
            AgentContext.clean();
            throw new RuntimeException(e);
        }
    }

    /**
     * 验证Agent定义
     * @param definition Agent定义
     */
    private void validAgentDefinition(AgentDefinition definition) {
        if (definition == null) {
            throw new RuntimeException("Agent not found" );
        }

        if (!definition.getEnabled()) {
            throw new RuntimeException("Agent is disabled, agentCode: " + definition.getAgentCode());
        }
    }

    /**
     * 设置租户信息
     * @param tenantId 租户ID
     */
    private void setTenantInfo(Long tenantId) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("租户不存在");
        }

        // 设置租户信息到AgentContext
        AgentContext agentContext = AgentContext.get();
        agentContext.setTenantId(tenant.getId());
        agentContext.setTenantCode(tenant.getCode());

        // 设置当前租户
        TenantUtils.setCurrentTenant(tenant.getId(), tenant.getCode());
    }
}
