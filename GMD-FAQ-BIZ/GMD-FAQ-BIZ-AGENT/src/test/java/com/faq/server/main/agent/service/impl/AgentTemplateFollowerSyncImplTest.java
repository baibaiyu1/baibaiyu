package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gmd.faq.server.main.agent.mapper.AgentDefinitionMapper;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTemplateFollowerSyncImplTest {
    private final AgentDefinitionMapper mapper = mock(AgentDefinitionMapper.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final AgentChatKeyRevoker chatKeyRevoker = mock(AgentChatKeyRevoker.class);
    private final MessagePublisher messagePublisher = mock(MessagePublisher.class);
    private final AgentTemplateFollowerSyncImpl sync =
            new AgentTemplateFollowerSyncImpl(mapper, approvalService, chatKeyRevoker, messagePublisher);

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(AgentDefinition.class) == null) {
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "agent-template-sync-test"),
                    AgentDefinition.class);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void changedFollowerIsUnpublishedAndShareAccessIsRevoked() {
        AgentDefinition follower = follower();
        when(mapper.selectList(any())).thenReturn(List.of(follower));
        when(mapper.update(any(AgentDefinition.class), any())).thenReturn(1);

        sync.syncFollowers(10L, 3L, "new prompt");

        ArgumentCaptor<Wrapper> select = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(select.capture());
        assertScoped(select.getValue(), 10L, 3L);

        ArgumentCaptor<AgentDefinition> update = ArgumentCaptor.forClass(AgentDefinition.class);
        ArgumentCaptor<Wrapper> condition = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(update.capture(), condition.capture());
        assertEquals("new prompt", update.getValue().getSystemPrompt());
        assertEquals(false, update.getValue().getPublished());
        assertEquals(false, update.getValue().getExternalShareEnabled());
        assertScoped(condition.getValue(), 10L, 3L);
        assertTrue(condition.getValue().getSqlSegment().contains("id"));

        verify(approvalService).cancelPendingForResource(ApprovalResourceType.AGENT, 55L,
                "智能体配置已变更，请重新提交发布申请");
        verify(chatKeyRevoker).revoke(follower);
        verify(messagePublisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "55");
    }

    @Test
    void noLongerFollowingAgentIsNotInvalidated() {
        when(mapper.selectList(any())).thenReturn(List.of(follower()));
        when(mapper.update(any(AgentDefinition.class), any())).thenReturn(0);

        sync.syncFollowers(10L, 3L, "new prompt");

        verifyNoInteractions(approvalService, chatKeyRevoker, messagePublisher);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void deletingTemplateDetachesAllReferencesAndKeepsPromptSnapshots() {
        AgentDefinition following = follower();
        AgentDefinition independent = follower();
        independent.setId(56L);
        independent.setFollowTemplate(false);
        independent.setSystemPrompt("custom prompt");
        when(mapper.selectList(any())).thenReturn(List.of(following, independent));
        when(mapper.update(isNull(), any())).thenReturn(1);

        sync.detachDeletedTemplates(List.of(10L), 3L);

        ArgumentCaptor<Wrapper> select = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(select.capture());
        assertTrue(select.getValue().getSqlSegment().contains("system_prompt_template_id"));
        assertTrue(select.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(((AbstractWrapper) select.getValue()).getParamNameValuePairs().values().contains(3L));

        ArgumentCaptor<Wrapper> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(2)).update(isNull(), updates.capture());
        for (Wrapper wrapper : updates.getAllValues()) {
            String set = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper) wrapper).getSqlSet();
            assertTrue(set.contains("system_prompt_template_id"));
            assertTrue(set.contains("follow_template"));
            assertTrue(set.contains("published"));
            assertTrue(set.contains("external_share_enabled"));
            assertFalse(set.matches("(?s).*\\bsystem_prompt\\s*=.*"));
            assertTrue(wrapper.getSqlSegment().contains("tenant_id"));
            assertTrue(((AbstractWrapper) wrapper).getParamNameValuePairs().values().contains(3L));
            assertTrue(((AbstractWrapper) wrapper).getParamNameValuePairs().values().contains(10L));
        }
        verify(approvalService).cancelPendingForResource(ApprovalResourceType.AGENT, 55L,
                "智能体配置已变更，请重新提交发布申请");
        verify(approvalService).cancelPendingForResource(ApprovalResourceType.AGENT, 56L,
                "智能体配置已变更，请重新提交发布申请");
        verify(chatKeyRevoker).revoke(following);
        verify(chatKeyRevoker).revoke(independent);
        verify(messagePublisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "55");
        verify(messagePublisher).publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, "56");
    }

    @Test
    void changedReferenceDuringDeletionIsNotInvalidated() {
        when(mapper.selectList(any())).thenReturn(List.of(follower()));
        when(mapper.update(isNull(), any())).thenReturn(0);

        sync.detachDeletedTemplates(List.of(10L), 3L);

        verifyNoInteractions(approvalService, chatKeyRevoker, messagePublisher);
    }

    @SuppressWarnings("rawtypes")
    private void assertScoped(Wrapper wrapper, Long templateId, Long tenantId) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("system_prompt_template_id"));
        assertTrue(sql.contains("follow_template"));
        assertTrue(sql.contains("tenant_id"));
        var values = ((AbstractWrapper) wrapper).getParamNameValuePairs().values();
        assertTrue(values.contains(templateId));
        assertTrue(values.contains(tenantId));
        assertTrue(values.contains(true));
    }

    private AgentDefinition follower() {
        AgentDefinition follower = new AgentDefinition();
        follower.setId(55L);
        follower.setTenantId(3L);
        follower.setAgentCode("follower-agent");
        follower.setSystemPromptTemplateId(10L);
        follower.setFollowTemplate(true);
        follower.setSystemPrompt("old prompt");
        follower.setPublished(true);
        follower.setExternalShareEnabled(true);
        return follower;
    }
}
