package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.agent.mapper.CodeExecutionConfigMapper;
import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.agent.service.CodeExecutionConfigService;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 描述：CodeExecutionConfigServiceImpl
 *
 **/
@Service
@RequiredArgsConstructor
public class CodeExecutionConfigServiceImpl extends ServiceImpl<CodeExecutionConfigMapper, CodeExecutionConfig> implements CodeExecutionConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final com.gmd.faq.server.main.common.cluster.core.MessagePublisher messagePublisher;

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public boolean doUpdate(CodeExecutionConfig config) {
        boolean updated = updateById(config);
        if (updated) refreshAgents(agentCodeExecutionService.getAgentIds(List.of(config.getId())));
        return updated;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public boolean deleteConfigs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        List<Long> agentIds = agentCodeExecutionService.getAgentIds(ids);
        boolean removed = removeByIds(ids);
        if (removed) refreshAgents(agentIds);
        return removed;
    }

    private void refreshAgents(List<Long> agentIds) {
        agentIds.stream().distinct().forEach(id -> messagePublisher.publishAfterCommit(
                com.gmd.faq.server.main.common.consts.RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(id)));
    }

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        getAgentDefinitions(agentCodeExecutionService.getAgentIds(ids)).forEach(agentDefinition -> {
            names.add(agentDefinition.getName());
        });

        return names;
    }

    /**
     * 根据Agent ID列表获取Agent定义列表
     *
     * @param agentIds Agent ID列表
     * @return Agent定义列表
     */
    private List<AgentDefinition> getAgentDefinitions(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return new ArrayList<>();
        }

        String subSql = agentIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        String sql = String.format("SELECT * FROM %s WHERE id IN (%s)", TableConst.AGENT, subSql);
        // 添加租户过滤（JdbcTemplate 绕过 MyBatis-Plus 拦截器）
        if (TenantUtils.getCurrentTenantId() != null) {
            sql += " AND tenant_id = " + TenantUtils.getCurrentTenantId();
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AgentDefinition agent = new AgentDefinition();
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            agent.setDescription(rs.getString("description"));
            return agent;
        });
    }
}
