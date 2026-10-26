package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.enums.McpToolExposureMode;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.common.vo.AgentMcpBindingVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AgentPublicationSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void displayOnlyEditsDoNotRequireAnotherApproval() throws Exception {
        AgentDefinitionVO before = agent();
        AgentDefinitionVO after = agent();
        after.setName("新标题");
        after.setTag("新分类");
        after.setVersion("2.0");
        after.setUiConfig(mapper.readTree("{\"openingMessage\":\"欢迎\"}"));

        assertEquals(AgentPublicationSnapshot.of(before), AgentPublicationSnapshot.of(after));
    }

    @Test
    void promptModelAndDependencyEditsRequireApproval() {
        AgentPublicationSnapshot original = AgentPublicationSnapshot.of(agent());

        AgentDefinitionVO promptChanged = agent();
        promptChanged.setSystemPrompt("new prompt");
        assertNotEquals(original, AgentPublicationSnapshot.of(promptChanged));

        AgentDefinitionVO modelChanged = agent();
        modelChanged.setModelConfigId(8L);
        assertNotEquals(original, AgentPublicationSnapshot.of(modelChanged));

        AgentDefinitionVO resilienceChanged = agent();
        resilienceChanged.setModelResilienceConfig(mapper.createObjectNode().put("maxAttempts", 2).put("fallbackModelId", "8"));
        assertNotEquals(original, AgentPublicationSnapshot.of(resilienceChanged));

        AgentDefinitionVO toolChanged = agent();
        toolChanged.setTool(List.of(2L, 3L));
        assertNotEquals(original, AgentPublicationSnapshot.of(toolChanged));

        AgentDefinitionVO bindingChanged = agent();
        bindingChanged.getMcpBindings().getFirst().setMcpToolIds(List.of(12L));
        assertNotEquals(original, AgentPublicationSnapshot.of(bindingChanged));
    }

    @Test
    void ReorderingDependenciesDoesNotRequireApproval() {
        AgentDefinitionVO before = agent();
        before.setTool(List.of(2L, 3L));
        AgentDefinitionVO after = agent();
        after.setTool(List.of(3L, 2L));

        assertEquals(AgentPublicationSnapshot.of(before), AgentPublicationSnapshot.of(after));
    }

    private AgentDefinitionVO agent() {
        AgentDefinitionVO agent = new AgentDefinitionVO();
        agent.setAgentType(AgentType.CUSTOM);
        agent.setAgentCode("assistant");
        agent.setName("标题");
        agent.setModelConfigId(7L);
        agent.setSystemPrompt("original prompt");
        agent.setTool(List.of(2L));
        agent.setMcp(List.of(10L));
        AgentMcpBindingVO binding = new AgentMcpBindingVO();
        binding.setMcpServerId(10L);
        binding.setExposureMode(McpToolExposureMode.SELECTED_ONLY);
        binding.setMcpToolIds(List.of(11L));
        agent.setMcpBindings(List.of(binding));
        return agent;
    }
}
