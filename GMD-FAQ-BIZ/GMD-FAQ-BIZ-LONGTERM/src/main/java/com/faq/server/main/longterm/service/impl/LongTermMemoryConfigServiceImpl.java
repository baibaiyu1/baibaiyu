package com.gmd.faq.server.main.longterm.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.LongTermMemoryConfig;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.longterm.mapper.LongTermMemoryConfigMapper;
import com.gmd.faq.server.main.longterm.service.AgentLongTermMemoryService;
import com.gmd.faq.server.main.longterm.service.LongTermMemoryConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 描述：LongTermMemoryConfigServiceImpl
 *
 **/
@Service
@RequiredArgsConstructor
public class LongTermMemoryConfigServiceImpl extends ServiceImpl<LongTermMemoryConfigMapper, LongTermMemoryConfig> implements LongTermMemoryConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentLongTermMemoryService agentLongTermMemoryService;

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        getAgentDefinitions(agentLongTermMemoryService.getAgentIds(ids)).forEach(agentDefinition -> {
            names.add(agentDefinition.getName());
        });

        return names;
    }

    private List<AgentDefinition> getAgentDefinitions(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return new ArrayList<>();
        }

        String subSql = agentIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        String sql = String.format("SELECT * FROM %s WHERE id IN (%s)", TableConst.AGENT, subSql);
        List<Object> params = new ArrayList<>();
        // 添加租户过滤（JdbcTemplate 绕过 MyBatis-Plus 拦截器）
        if (TenantUtils.getCurrentTenantId() != null) {
            sql += " AND tenant_id = ?";
            params.add(TenantUtils.getCurrentTenantId());
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AgentDefinition agent = new AgentDefinition();
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            agent.setDescription(rs.getString("description"));
            return agent;
        }, params.toArray());
    }
}
