package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.agent.mapper.AgentChatKeyMapper;
import com.gmd.faq.server.main.common.entity.AgentChatKey;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.RedisKeyBuilder;
import com.gmd.faq.server.main.common.util.RedisUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Deletes a share link with its agent update and clears its cache after commit. */
@Component
@RequiredArgsConstructor
class AgentChatKeyRevoker {

    private final AgentChatKeyMapper agentChatKeyMapper;
    private final RedisUtils redisUtils;

    void revoke(AgentDefinition agent) {
        var query = Wrappers.<AgentChatKey>lambdaQuery()
                .eq(AgentChatKey::getAgentCode, agent.getAgentCode())
                .eq(AgentChatKey::getTenantId, agent.getTenantId());
        List<String> keys = agentChatKeyMapper.selectList(query).stream()
                .map(AgentChatKey::getChatKey).toList();
        agentChatKeyMapper.delete(query);
        Runnable clearCache = () -> keys.forEach(key ->
                redisUtils.delete(RedisKeyBuilder.globalKey("chatkey:" + key)));
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearCache.run();
                }
            });
        } else {
            clearCache.run();
        }
    }
}
