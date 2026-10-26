package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.agent.mapper.AgentDefinitionMapper;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.prompt.service.AgentTemplateFollowerSync;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
class AgentTemplateFollowerSyncImpl implements AgentTemplateFollowerSync {
    private final AgentDefinitionMapper agentDefinitionMapper;
    private final ApprovalService approvalService;
    private final AgentChatKeyRevoker chatKeyRevoker;
    private final MessagePublisher messagePublisher;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void syncFollowers(Long templateId, Long tenantId, String content) {
        var followers = agentDefinitionMapper.selectList(Wrappers.<AgentDefinition>lambdaQuery()
                .eq(AgentDefinition::getSystemPromptTemplateId, templateId)
                .eq(AgentDefinition::getFollowTemplate, true)
                .eq(AgentDefinition::getTenantId, tenantId));
        // Following a template opts an agent into its editor's content changes, regardless of agent owner.
        for (AgentDefinition follower : followers) {
            AgentDefinition update = new AgentDefinition();
            update.setSystemPrompt(content);
            update.setPublished(false);
            update.setExternalShareEnabled(false);
            int updated = agentDefinitionMapper.update(update, Wrappers.<AgentDefinition>lambdaUpdate()
                    .eq(AgentDefinition::getId, follower.getId())
                    .eq(AgentDefinition::getSystemPromptTemplateId, templateId)
                    .eq(AgentDefinition::getFollowTemplate, true)
                    .eq(AgentDefinition::getTenantId, tenantId));
            if (updated == 0) {
                continue;
            }
            approvalService.cancelPendingForResource(ApprovalResourceType.AGENT, follower.getId(),
                    "智能体配置已变更，请重新提交发布申请");
            chatKeyRevoker.revoke(follower);
            messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL,
                    String.valueOf(follower.getId()));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void detachDeletedTemplates(List<Long> templateIds, Long tenantId) {
        var agents = agentDefinitionMapper.selectList(Wrappers.<AgentDefinition>lambdaQuery()
                .in(AgentDefinition::getSystemPromptTemplateId, templateIds)
                .eq(AgentDefinition::getTenantId, tenantId));
        for (AgentDefinition agent : agents) {
            int updated = agentDefinitionMapper.update(null, Wrappers.<AgentDefinition>lambdaUpdate()
                    .set(AgentDefinition::getSystemPromptTemplateId, null)
                    .set(AgentDefinition::getFollowTemplate, false)
                    .set(AgentDefinition::getPublished, false)
                    .set(AgentDefinition::getExternalShareEnabled, false)
                    .eq(AgentDefinition::getId, agent.getId())
                    .eq(AgentDefinition::getSystemPromptTemplateId, agent.getSystemPromptTemplateId())
                    .eq(AgentDefinition::getTenantId, tenantId));
            if (updated == 0) {
                continue;
            }
            approvalService.cancelPendingForResource(ApprovalResourceType.AGENT, agent.getId(),
                    "智能体配置已变更，请重新提交发布申请");
            chatKeyRevoker.revoke(agent);
            messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL,
                    String.valueOf(agent.getId()));
        }
    }
}
