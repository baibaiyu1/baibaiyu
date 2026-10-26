package com.gmd.faq.server.main.prompt.service.impl;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.SystemPromptTemplate;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.prompt.mapper.SystemPromptTemplateMapper;
import com.gmd.faq.server.main.prompt.service.AgentTemplateFollowerSync;
import com.gmd.faq.server.main.prompt.service.SystemPromptTemplateService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 系统提示词模板Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class SystemPromptTemplateServiceImpl extends ServiceImpl<SystemPromptTemplateMapper, SystemPromptTemplate> implements SystemPromptTemplateService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentTemplateFollowerSync followerSync;

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        ArrayList<Object> names = new ArrayList<>();
        getAgentDefinitions(ids).forEach(agentDefinition -> {
            names.add(agentDefinition.getName());
        });

        return names;
    }

    @Override
    public List<String> listCategories() {
        return this.lambdaQuery()
                .select(SystemPromptTemplate::getCategory)
                .isNotNull(SystemPromptTemplate::getCategory)
                .groupBy(SystemPromptTemplate::getCategory)
                .list()
                .stream()
                .map(SystemPromptTemplate::getCategory)
                .filter(category -> category != null && !category.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        Long tenantId = currentTenantId("删除");
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("提示词模板 ID 不能为空");
        }
        List<Long> templateIds = List.copyOf(new LinkedHashSet<>(ids));
        String placeholders = String.join(",", Collections.nCopies(templateIds.size(), "?"));
        Object[] args = new Object[templateIds.size() + 1];
        for (int i = 0; i < templateIds.size(); i++) {
            args[i] = templateIds.get(i);
        }
        args[templateIds.size()] = tenantId;

        String where = " WHERE id IN (" + placeholders + ") AND tenant_id = ?";
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT id FROM " + TableConst.PROMPT + where + " FOR UPDATE", Long.class, args);
        if (found.size() != templateIds.size()) {
            return false;
        }
        followerSync.detachDeletedTemplates(templateIds, tenantId);
        int deleted = jdbcTemplate.update("DELETE FROM " + TableConst.PROMPT + where, args);
        if (deleted != templateIds.size()) {
            throw new IllegalStateException("删除提示词模板失败");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean doUpdate(SystemPromptTemplate entity) {
        Long tenantId = currentTenantId("更新");
        SystemPromptTemplate before = getById(entity.getId());
        if (before == null || !tenantId.equals(before.getTenantId())) {
            return false;
        }

        String lockSql = "SELECT content FROM " + TableConst.PROMPT
                + " WHERE id = ? AND tenant_id = ? FOR UPDATE";
        List<String> previousContent = jdbcTemplate.queryForList(lockSql, String.class, entity.getId(), tenantId);
        if (previousContent.isEmpty()) {
            return false;
        }
        entity.setTenantId(tenantId);
        boolean result = updateById(entity);
        if (!result) {
            return false;
        }

        SystemPromptTemplate updated = getById(entity.getId());
        if (updated == null || !tenantId.equals(updated.getTenantId())) {
            throw new IllegalStateException("更新后的提示词模板不属于当前租户");
        }
        if (updated.getContent() == null) {
            throw new IllegalStateException("提示词模板内容不能为空");
        }
        if (!Objects.equals(previousContent.getFirst(), updated.getContent())) {
            followerSync.syncFollowers(updated.getId(), tenantId, updated.getContent());
        }
        return result;
    }

    private Long currentTenantId(String operation) {
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(operation + "提示词模板时缺少租户上下文");
        }
        return tenantId;
    }

    private List<AgentDefinition> getAgentDefinitions(List<Long> systemPromptId) {
        if (systemPromptId == null || systemPromptId.isEmpty()) {
            return new ArrayList<>();
        }

        Long tenantId = currentTenantId("查询");
        String placeholders = String.join(",", Collections.nCopies(systemPromptId.size(), "?"));
        String sql = "SELECT id, name FROM " + TableConst.AGENT
                + " WHERE system_prompt_template_id IN (" + placeholders + ") AND tenant_id = ?";
        Object[] args = new Object[systemPromptId.size() + 1];
        for (int i = 0; i < systemPromptId.size(); i++) {
            args[i] = systemPromptId.get(i);
        }
        args[systemPromptId.size()] = tenantId;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AgentDefinition agent = new AgentDefinition();
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            return agent;
        }, args);
    }
}
