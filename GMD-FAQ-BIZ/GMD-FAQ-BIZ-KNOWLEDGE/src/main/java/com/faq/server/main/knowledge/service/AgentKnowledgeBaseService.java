package com.gmd.faq.server.main.knowledge.service;

import com.gmd.faq.server.main.common.entity.AgentKnowledgeBase;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 智能体知识库关联Service
 *
 */
public interface AgentKnowledgeBaseService extends IService<AgentKnowledgeBase> {
    List<Long> getAgentIds(List<Long> knowledgeIds);
    List<Long> getKnowledgeIds(Long agentDefinitionId);
    Boolean insertAgentKnowledge(Long agentDefinitionId, List<Long> knowledgeIds);
    Boolean deleteAgentKnowledge(List<Long> agentIds);
    Boolean saveAgentKnowledge(Long agentDefinitionId, List<Long> knowledgeIds);
}
