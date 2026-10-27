package com.gmd.faq.server.main.tool.service.impl;

import com.gmd.faq.server.main.common.entity.AgentTool;
import com.gmd.faq.server.main.tool.mapper.AgentToolMapper;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 智能体工具关联Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class AgentToolServiceImpl extends ServiceImpl<AgentToolMapper, AgentTool> implements AgentToolService {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<Long> getAgentIds(List<Long> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        var result = new java.util.LinkedHashSet<>(lambdaQuery()
                .in(AgentTool::getToolId, tools)
                .list()
                .stream()
                .map(AgentTool::getAgentDefinitionId)
                .distinct()
                .collect(Collectors.toList()));
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) throw new IllegalStateException("查询工具依赖缺少租户上下文");
        String placeholders = String.join(",", java.util.Collections.nCopies(tools.size(), "?"));
        String sql = "SELECT DISTINCT a.agent_definition_id FROM " + TableConst.AGENT_SKILL + " a JOIN "
                + TableConst.SKILL_TOOL + " s ON a.skill_package_id = s.skill_id AND a.tenant_id = s.tenant_id"
                + " WHERE a.tenant_id = ? AND s.tool_id IN (" + placeholders + ")";
        var args = new java.util.ArrayList<Object>();
        args.add(tenantId); args.addAll(tools);
        result.addAll(jdbcTemplate.queryForList(sql, Long.class, args.toArray()));
        return List.copyOf(result);
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
