package com.gmd.faq.server.main.agent.service.impl;

import com.gmd.faq.server.main.agent.mapper.AgentSubAgentMapper;
import com.gmd.faq.server.main.agent.service.AgentSubAgentService;
import com.gmd.faq.server.main.common.entity.AgentSubAgent;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能体子智能体关联Service实现
 *
 */
@Service
public class AgentSubAgentServiceImpl extends ServiceImpl<AgentSubAgentMapper, AgentSubAgent> implements AgentSubAgentService {
    @Override
    public List<Long> getSubAgentIds(Long agentDefinitionId) {
        return lambdaQuery()
                .eq(AgentSubAgent::getParentAgentId, agentDefinitionId)
                .list()
                .stream()
                .map(AgentSubAgent::getSubAgentId).toList();
    }

    @Override
    public Boolean insertSubAgent(Long agentDefinitionId, List<Long> subAgentIds) {
        subAgentIds.forEach(subAgentId -> {
            save(new AgentSubAgent(null, null, agentDefinitionId, subAgentId));
        });

        return true;
    }

    @Override
    public Boolean deleteSubAgent(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return true;
        }
        return lambdaUpdate().in(AgentSubAgent::getParentAgentId, agentIds).remove();
    }

    @Override
    public Boolean saveSubAgent(Long agentDefinitionId, List<Long> subAgentIds) {
        deleteSubAgent(List.of(agentDefinitionId));
        insertSubAgent(agentDefinitionId, subAgentIds);

        return Boolean.TRUE;
    }
}
