package com.gmd.faq.server.main.tool.service.impl;

import com.gmd.faq.server.main.common.entity.AgentTool;
import com.gmd.faq.server.main.tool.mapper.AgentToolMapper;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体工具关联Service实现
 *
 */
@Service
public class AgentToolServiceImpl extends ServiceImpl<AgentToolMapper, AgentTool> implements AgentToolService {

    @Override
    public List<Long> getAgentIds(List<Long> tools) {
        return lambdaQuery()
                .in(AgentTool::getToolId, tools)
                .list()
                .stream()
                .map(AgentTool::getAgentDefinitionId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getToolIds(Long agentDefinitionId) {
        return lambdaQuery()
                .eq(AgentTool::getAgentDefinitionId, agentDefinitionId)
                .list()
                .stream()
                .map(AgentTool::getToolId)
                .collect(Collectors.toList());
    }

    @Override
    public Boolean insertAgentTool(Long agentDefinitionId, List<Long> toolIds) {
        toolIds.forEach(toolId -> {
            save(new AgentTool(null, null, agentDefinitionId, toolId));
        });
        return true;
    }

    @Override
    public Boolean deleteAgentTool(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return true;
        }
        return lambdaUpdate().in(AgentTool::getAgentDefinitionId, agentIds).remove();
    }

    @Override
    public Boolean saveAgentTool(Long agentDefinitionId, List<Long> toolIds) {
        deleteAgentTool(List.of(agentDefinitionId));
        insertAgentTool(agentDefinitionId, toolIds);

        return Boolean.TRUE;
    }
}
