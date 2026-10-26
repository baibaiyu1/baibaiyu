package com.gmd.faq.server.main.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.entity.AgentChatKey;

/**
 * 描述：智能体对话Key服务
 *
 **/
public interface AgentChatKeyService extends IService<AgentChatKey> {
    /**
     * 获取或生成智能体对话Key
     *
     * @param agentId 智能体ID
     * @param refresh 是否刷新Key
     * @return 对话Key
     */
    String getChatKey(Long agentId, boolean refresh);

    /** Administrator approval for external sharing; disabling revokes the current link. */
    void setExternalSharing(Long agentId, boolean enabled);

    /**
     * 根据ChatKey获取AgentCode
     * 优先从Redis缓存获取，缓存未命中则从数据库查询并回填缓存
     * 实现缓存穿透防护（布隆过滤器/空值缓存）
     *
     * @param chatKey 对话Key
     * @return AgentCode，如果不存在则返回null
     */
    String getAgentCodeByChatKey(String chatKey);

    Long getAgentIdByChatKey(String chatKey);
}
