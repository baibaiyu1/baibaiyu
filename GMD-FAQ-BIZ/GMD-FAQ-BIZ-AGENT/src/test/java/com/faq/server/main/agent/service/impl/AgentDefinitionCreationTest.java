package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.agent.service.AgentReasoningPolicy;
import com.gmd.faq.server.main.agent.service.AgentSubAgentService;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.enums.A2aType;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.enums.ToolChoiceStrategy;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.knowledge.service.AgentKnowledgeBaseService;
import com.gmd.faq.server.main.longterm.service.AgentLongTermMemoryService;
import com.gmd.faq.server.main.mcp.service.AgentMcpServerService;
import com.gmd.faq.server.main.skill.service.AgentSkillPackageService;
import com.gmd.faq.server.main.studio.service.AgentStudioService;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.gmd.faq.server.main.workflowbiz.service.AgentWorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentDefinitionCreationTest {
    @Mock private AgentReasoningPolicy reasoningPolicy;
    @Mock private AgentHookService hooks;
    @Mock private AgentA2aService a2a;
    @Mock private AgentSubAgentService subAgents;
    @Mock private AgentToolService tools;
    @Mock private AgentMcpServerService mcp;
    @Mock private AgentSkillPackageService skills;
    @Mock private AgentKnowledgeBaseService knowledge;
    @Mock private AgentWorkflowService workflows;
    @Mock private AgentStudioService studio;
    @Mock private AgentCodeExecutionService codeExecution;
    @Mock private AgentLongTermMemoryService longTermMemory;
    @Spy @InjectMocks private AgentDefinitionServiceImpl service;

    private final List<AgentDefinition> saved = new ArrayList<>();
    private UserDetail user;

    @BeforeEach
    void setUp() {
        user = UserDetail.builder().id(100L).tenantId(1L).tenantCode("test").deptId(5L)
                .authType("ACCOUNT").perms(List.of()).build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        doAnswer(invocation -> {
            AgentDefinition entity = invocation.getArgument(0);
            entity.setId(7L + saved.size());
            saved.add(entity);
            return true;
        }).when(service).save(any(AgentDefinition.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void basicInformationCreatesPrivateDisabledDraftWithoutModelOrTemplate() {
        AgentDefinitionVO request = basicRequest();
        request.setOwnerId(999L);
        request.setVisibility("TENANT");
        request.setPublished(true);
        request.setExternalShareEnabled(true);

        assertTrue(service.saveAgentDefinition(request));

        AgentDefinition entity = saved.getFirst();
        assertEquals(7L, request.getId());
        assertEquals(request.getAgentCode(), entity.getAgentCode());
        assertTrue(entity.getAgentCode().matches("test-agent-[0-9]+"));
        assertEquals(AgentType.CUSTOM, entity.getAgentType());
        assertEquals(100L, entity.getOwnerId());
        assertEquals(5L, entity.getResourceDeptId());
        assertEquals("PRIVATE", entity.getVisibility());
        assertFalse(entity.getEnabled());
        assertFalse(entity.getPublished());
        assertFalse(entity.getExternalShareEnabled());
        assertNull(entity.getModelConfigId());
        assertNull(entity.getSystemPromptTemplateId());
        assertFalse(entity.getFollowTemplate());
        assertEquals(ToolChoiceStrategy.AUTO, entity.getToolChoiceStrategy());
        assertTrue(entity.getEnableMemory());
        assertEquals(50, entity.getMaxIterations());
        verify(hooks).saveAgentHook(7L, List.of());
        verify(subAgents).saveSubAgent(7L, List.of());
        verify(tools).saveAgentTool(7L, List.of());
        verify(mcp).saveAgentMcpServer(7L, List.of(), null);
        verify(skills).saveAgentSkillPackage(7L, List.of());
        verify(knowledge).saveAgentKnowledge(7L, List.of());
        verify(workflows).saveAgentWorkflow(7L, List.of());
    }

    @Test
    void automaticallyGeneratedCodesAreDistinctAndSupportMissingTenantCode() {
        AgentDefinitionVO first = basicRequest();
        AgentDefinitionVO second = basicRequest();
        second.setAgentCode(" ");
        service.saveAgentDefinition(first);
        service.saveAgentDefinition(second);
        assertNotEquals(first.getAgentCode(), second.getAgentCode());
        user.setTenantCode(null);
        AgentDefinitionVO withoutPrefix = basicRequest();
        service.saveAgentDefinition(withoutPrefix);
        assertTrue(withoutPrefix.getAgentCode().matches("agent-[0-9]+"));
    }

    @Test
    void bothA2aDraftTypesUseGeneratedCodeWithoutRequiringConnectionSettings() {
        for (A2aType type : A2aType.values()) {
            AgentDefinitionVO request = basicRequest();
            request.setAgentType(AgentType.A2A);
            AgentA2A binding = new AgentA2A(null, type, new ObjectMapper().createObjectNode());
            request.setAgentA2A(binding);

            assertTrue(service.saveAgentDefinition(request));

            assertEquals(request.getId(), binding.getAgentDefinitionId());
            assertEquals(request.getAgentCode(), binding.getA2aConfig().path("agentName").asText());
            assertFalse(request.getEnableMemory());
            assertFalse(request.getEnabled());
            verify(a2a).saveA2aConfig(binding);
        }
    }

    @Test
    void explicitCodesAndExistingConfigurationRemainSupported() {
        AgentDefinitionVO request = basicRequest();
        request.setAgentCode("existing-integration-code");
        request.setEnabled(true);
        request.setEnableMemory(false);
        request.setMaxIterations(12);
        request.setModelConfigId(10L);

        service.saveAgentDefinition(request);

        AgentDefinition entity = saved.getFirst();
        assertEquals("existing-integration-code", entity.getAgentCode());
        assertTrue(entity.getEnabled());
        assertFalse(entity.getEnableMemory());
        assertEquals(12, entity.getMaxIterations());
        assertEquals(10L, entity.getModelConfigId());
    }

    private AgentDefinitionVO basicRequest() {
        AgentDefinitionVO request = new AgentDefinitionVO();
        request.setName("客服助手");
        request.setDescription("解答客户问题");
        return request;
    }
}
