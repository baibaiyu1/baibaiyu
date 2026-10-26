package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.agent.mapper.AgentTagMapper;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.AgentTag;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AgentDefinitionTagsTest {

    @Mock private AgentHookService hookService;
    @Mock private com.gmd.faq.server.main.agent.service.AgentReasoningPolicy reasoningPolicy;
    @Mock private AgentA2aService a2aService;
    @Mock private MessagePublisher messagePublisher;
    @Mock private ResourceVisibilityService resourceVisibilityService;
    @Mock private AgentTagMapper agentTagMapper;
    @Spy @InjectMocks private AgentDefinitionServiceImpl service;

    private final ObjectMapper mapper = new ObjectMapper();

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
    void createSavesNormalizedTagsAndLegacyPrimaryTag() {
        AgentDefinitionVO request = request();
        request.setTags(List.of(" first ", "second", "first"));
        doAnswer(invocation -> {
            AgentDefinition entity = invocation.getArgument(0);
            entity.setId(7L);
            return true;
        }).when(service).save(any(AgentDefinition.class));

        assertTrue(service.saveAgentDefinition(request));

        ArgumentCaptor<AgentDefinition> saved = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service).save(saved.capture());
        assertEquals("first", saved.getValue().getTag());
        assertEquals(mapper.valueToTree(List.of("first", "second")), saved.getValue().getTagsJson());
    }

    @Test
    void updateWithEmptyTagsClearsBothTagFields() throws Exception {
        AgentDefinitionVO request = request();
        request.setId(7L);
        request.setTag("stale client primary");
        request.setTags(List.of());
        stubUpdate(request, existingAgent());

        assertTrue(service.updateAgentDefinition(request));

        AgentDefinition updated = capturedUpdate();
        assertEquals("", updated.getTag());
        assertEquals(mapper.readTree("[]"), updated.getTagsJson());
    }

    @Test
    void updateWithoutTagFieldsPreservesExistingTags() throws Exception {
        AgentDefinitionVO request = request();
        request.setId(7L);
        AgentDefinition existing = existingAgent();
        existing.setTag("first");
        existing.setTagsJson(mapper.readTree("[\"first\",\"second\"]"));
        stubUpdate(request, existing);

        assertTrue(service.updateAgentDefinition(request));

        AgentDefinition updated = capturedUpdate();
        assertEquals("first", updated.getTag());
        assertEquals(existing.getTagsJson(), updated.getTagsJson());
    }

    @Test
    void listTagsReadsCatalogEvenWhenNoAgentUsesTag() {
        AgentTag stored = new AgentTag();
        stored.setName("independent");
        doReturn(List.of(stored)).when(agentTagMapper).selectList(any());

        assertEquals(List.of("independent"), service.listTags());
        verify(agentTagMapper).selectList(any());
    }

    @Test
    void createTagInsertsNormalizedNameAndRejectsBlank() {
        assertEquals("new tag", service.createTag(" new tag "));
        ArgumentCaptor<AgentTag> inserted = ArgumentCaptor.forClass(AgentTag.class);
        verify(agentTagMapper).insert(inserted.capture());
        assertEquals("new tag", inserted.getValue().getName());
        assertThrows(IllegalArgumentException.class, () -> service.createTag("   "));
    }

    @Test
    void deleteTagRemovesUnusedCatalogEntry() {
        AgentTag stored = new AgentTag();
        stored.setId(9L);
        stored.setName("unused");
        doReturn(stored).when(agentTagMapper).selectOne(any());
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        assertTrue(service.deleteTag(" unused "));
        verify(agentTagMapper).deleteById(9L);
    }

    @Test
    void deleteTagRejectsReferencedAgentAndKeepsCatalogEntry() {
        AgentTag stored = new AgentTag();
        stored.setId(9L);
        stored.setName("in-use");
        doReturn(stored).when(agentTagMapper).selectOne(any());
        AgentDefinition agent = existingAgent();
        agent.setName("客服助手");
        agent.setTagsJson(mapper.createArrayNode().add("in-use"));
        doReturn(List.of(agent)).when(service).list(any(Wrapper.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteTag("in-use"));
        assertTrue(error.getMessage().contains("客服助手"));
        verify(agentTagMapper, never()).deleteById(any());
    }

    private void stubUpdate(AgentDefinitionVO request, AgentDefinition existing) {
        doReturn(existing).when(service).getById(7L);
        doReturn(request).when(service).agentDefinitionDetail(7L);
        doReturn(true).when(service).updateById(any(AgentDefinition.class));
    }

    private AgentDefinition capturedUpdate() {
        ArgumentCaptor<AgentDefinition> updated = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service).updateById(updated.capture());
        return updated.getValue();
    }

    private AgentDefinitionVO request() {
        AgentDefinitionVO request = new AgentDefinitionVO();
        request.setAgentType(AgentType.A2A);
        request.setAgentCode("tag-agent");
        request.setAgentA2A(new AgentA2A());
        return request;
    }

    private AgentDefinition existingAgent() {
        AgentDefinition existing = new AgentDefinition();
        existing.setId(7L);
        existing.setAgentCode("tag-agent");
        existing.setOwnerId(100L);
        existing.setVisibility("PRIVATE");
        return existing;
    }
}
