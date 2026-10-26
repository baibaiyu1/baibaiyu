package com.gmd.faq.server.main.agent.service.impl;

import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.prompt.service.SystemPromptTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDefinitionFollowTemplateTest {

    @Mock private AgentHookService hookService;
    @Mock private com.gmd.faq.server.main.agent.service.AgentReasoningPolicy reasoningPolicy;
    @Mock private AgentA2aService a2aService;
    @Mock private SystemPromptTemplateService templateService;
    @Mock private MessagePublisher messagePublisher;
    @Mock private ApprovalService approvalService;
    @Mock private AgentChatKeyRevoker chatKeyRevoker;
    @Spy @InjectMocks private AgentDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL, UserDetail.builder().id(100L).tenantId(1L)
                .authType("ACCOUNT").perms(List.of()).build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createUsesCurrentTemplateContentInsteadOfSubmittedPrompt() {
        AgentDefinitionVO request = agentRequest();
        when(templateService.getById(9L)).thenReturn(template("current template"));
        doAnswer(invocation -> {
            AgentDefinition entity = invocation.getArgument(0);
            entity.setId(7L);
            return true;
        }).when(service).save(any(AgentDefinition.class));

        assertTrue(service.saveAgentDefinition(request));

        ArgumentCaptor<AgentDefinition> saved = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service).save(saved.capture());
        assertEquals("current template", saved.getValue().getSystemPrompt());
        assertEquals(9L, saved.getValue().getSystemPromptTemplateId());
        assertEquals(7L, request.getId());
    }

    @Test
    void updateUsesCurrentTemplateContentInsteadOfSubmittedPrompt() {
        AgentDefinitionVO request = agentRequest();
        request.setId(7L);
        request.setSystemPromptTemplateId(null);
        AgentDefinition existing = existingAgent();
        doReturn(existing).when(service).getById(7L);
        doReturn(request).when(service).agentDefinitionDetail(7L);
        doReturn(true).when(service).updateById(any(AgentDefinition.class));
        when(templateService.getById(9L)).thenReturn(template("updated template"));

        assertTrue(service.updateAgentDefinition(request));

        ArgumentCaptor<AgentDefinition> updated = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service, times(2)).updateById(updated.capture());
        assertEquals("updated template", updated.getAllValues().getFirst().getSystemPrompt());
        assertEquals("updated template", request.getSystemPrompt());
        verify(templateService).getById(9L);
    }

    @Test
    void createRejectsMissingTemplateBeforeSaving() {
        AgentDefinitionVO request = agentRequest();

        assertThrows(IllegalArgumentException.class, () -> service.saveAgentDefinition(request));

        verify(templateService).getById(9L);
        verify(service, never()).save(any(AgentDefinition.class));
        verifyNoInteractions(hookService, a2aService);
    }

    @Test
    void updateRejectsMissingTemplateBeforeWriting() {
        AgentDefinitionVO request = agentRequest();
        request.setId(7L);
        doReturn(existingAgent()).when(service).getById(7L);
        doReturn(request).when(service).agentDefinitionDetail(7L);

        assertThrows(IllegalArgumentException.class, () -> service.updateAgentDefinition(request));

        verify(templateService).getById(9L);
        verify(service, never()).updateById(any(AgentDefinition.class));
        verifyNoInteractions(hookService, a2aService, messagePublisher);
    }

    private AgentDefinitionVO agentRequest() {
        AgentDefinitionVO request = new AgentDefinitionVO();
        request.setAgentType(AgentType.A2A);
        request.setAgentCode("template-agent");
        request.setAgentA2A(new AgentA2A());
        request.setFollowTemplate(true);
        request.setSystemPromptTemplateId(9L);
        request.setSystemPrompt("stale client prompt");
        return request;
    }

    private AgentDefinition existingAgent() {
        AgentDefinition existing = new AgentDefinition();
        existing.setId(7L);
        existing.setAgentCode("template-agent");
        existing.setOwnerId(100L);
        existing.setVisibility("PRIVATE");
        existing.setFollowTemplate(true);
        existing.setSystemPromptTemplateId(9L);
        return existing;
    }

    private SystemPromptTemplate template(String content) {
        SystemPromptTemplate template = new SystemPromptTemplate();
        template.setContent(content);
        return template;
    }
}
