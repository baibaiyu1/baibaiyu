package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.enums.McpToolExposureMode;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.common.vo.AgentMcpBindingVO;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Only execution-affecting settings belong to an agent's publication approval. */
record AgentPublicationSnapshot(
        List<Object> settings,
        Set<Long> hooks,
        Set<Long> tools,
        Set<Long> skills,
        Set<Long> workflows,
        Set<Long> mcpServers,
        Set<McpBinding> mcpBindings,
        Set<Long> subAgents,
        Set<Long> knowledgeBases,
        Long studioConfigId,
        Long codeExecutionConfigId,
        Long longTermMemoryConfigId,
        A2aConfig a2aConfig) {

    static AgentPublicationSnapshot of(AgentDefinitionVO agent) {
        AgentA2A a2a = agent.getAgentA2A();
        return new AgentPublicationSnapshot(
                Arrays.asList(
                        agent.getAgentType(), agent.getAgentCode(), agent.getDescription(),
                        agent.getModelConfigId(), agent.getModelParamsOverride(), agent.getModelResilienceConfig(), agent.getRagConfig(),
                        agent.getToolChoiceStrategy(), agent.getSpecificToolName(),
                        agent.getSystemPromptTemplateId(), agent.getFollowTemplate(), agent.getSystemPrompt(),
                        agent.getSensitiveWordConfigId(), agent.getSensitiveFilterEnabled(),
                        agent.getMaxIterations(), agent.getEnablePlanning(), agent.getMaxSubtasks(),
                        agent.getRequirePlanConfirmation(), agent.getShowToolProcess(),
                        agent.getEnableMemory(), agent.getEnableMemoryCompression(),
                        agent.getMemoryCompressionConfig(), agent.getStructuredOutputEnabled(),
                        agent.getStructuredOutputReminder(), agent.getStructuredOutputSchema()),
                ids(agent.getHook()), ids(agent.getTool()), ids(agent.getSkill()),
                ids(agent.getWorkflow()), ids(agent.getMcp()), bindings(agent.getMcpBindings()),
                ids(agent.getSubAgent()), ids(agent.getKnowledgeBase()),
                agent.getStudioConfigId(), agent.getCodeExecutionConfigId(),
                agent.getLongTermMemoryConfigId(),
                a2a == null ? null : new A2aConfig(a2a.getA2aType(), a2a.getA2aConfig()));
    }

    private static Set<Long> ids(List<Long> values) {
        return values == null ? Set.of() : values.stream()
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<McpBinding> bindings(List<AgentMcpBindingVO> values) {
        return values == null ? Set.of() : values.stream()
                .filter(item -> item != null && item.getMcpServerId() != null)
                .map(item -> new McpBinding(item.getMcpServerId(),
                        item.getExposureMode() == null ? McpToolExposureMode.ALL_GLOBAL : item.getExposureMode(),
                        ids(item.getMcpToolIds())))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record McpBinding(Long serverId, McpToolExposureMode exposureMode, Set<Long> toolIds) {
    }

    private record A2aConfig(Object type, JsonNode config) {
    }
}
