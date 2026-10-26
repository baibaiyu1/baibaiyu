package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.entity.AgentSubAgent;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 智能体子智能体关联Service
 *
 */
public interface AgentSubAgentService extends IService<AgentSubAgent> {
    List<Long> getSubAgentIds(Long agentDefinitionId);
    Boolean insertSubAgent(Long agentDefinitionId, List<Long> subAgentIds);
    Boolean deleteSubAgent(List<Long> agentIds);
    Boolean saveSubAgent(Long agentDefinitionId, List<Long> subAgentIds);
}
