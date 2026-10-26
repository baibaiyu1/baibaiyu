package com.gmd.faq.server.main.tool.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.AgentTool;
import com.gmd.faq.server.main.common.entity.SkillTool;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ToolInfoWrapper;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.tool.mapper.ISkillToolMapper;
import com.gmd.faq.server.main.tool.mapper.ToolMapper;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.gmd.faq.server.main.tool.service.ToolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class ToolServiceImpl extends ServiceImpl<ToolMapper, ToolConfig> implements ToolService {
    private final JdbcTemplate jdbcTemplate;
    private final ISkillToolMapper iSkillToolMapper;
    private final AgentToolService agentToolService;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;

    @Override
    public boolean save(ToolConfig entity) {
        // 自定义工具记录归属人；内置工具（tool_type=BUILTIN）由代码同步维持为无归属的公共资源池
        if (entity != null && entity.getToolType() != ToolType.BUILTIN) {
            ResourceOwnershipSupport.initOwnership(entity);
        }
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteTools(List<Long> ids) {
        // 删除前先获取关联的智能体ID，以便后续触发重新注册
        List<Long> agentIds = agentToolService.getAgentIds(ids);
        listByIds(ids).forEach(toolConfig -> {
            if (toolConfig.getToolType() != ToolType.BUILTIN) {
                // 资源级判权：列表能看到 ≠ 能删，归属人或管理员才能删除
                ResourceOwnershipSupport.assertManageable(toolConfig, "删除");
                removeById(toolConfig.getId());
            }
        });

        agentToolService.remove(new LambdaQueryWrapper<AgentTool>().in(AgentTool::getToolId, ids));
        iSkillToolMapper.delete(new LambdaQueryWrapper<SkillTool>().in(SkillTool::getToolId, ids));
        publishAgentReregister(agentIds);

        return true;
    }

    @Override
    public void SyncConfigToDatabase(List<ToolInfoWrapper> toolInfos) {
        // 1. 获取所有租户（供 GLOBAL 作用域使用）
        List<TenantRecord> allTenants = getAllTenants();
        List<Long> allTenantIds = allTenants.stream().map(t -> t.id).toList();
        Map<String, Long> tenantCodeToId = new HashMap<>();
        for (TenantRecord t : allTenants) {
            if (t.code != null) {
                tenantCodeToId.put(t.code, t.id);
            }
        }

        // 2. 清理已从代码中移除的内置工具（跨所有租户）
        List<String> currentClassPaths = toolInfos.stream()
                .map(ToolInfoWrapper::getClassPath).toList();
        if (currentClassPaths.isEmpty()) {
            jdbcTemplate.update("DELETE FROM " + TableConst.TOOL + " WHERE tool_type = 'BUILTIN'");
            return;
        }
        List<String> existingClassPaths = jdbcTemplate.queryForList(
                "SELECT DISTINCT class_path FROM " + TableConst.TOOL + " WHERE tool_type = 'BUILTIN'",
                String.class);
        Set<String> currentSet = new HashSet<>(currentClassPaths);
        for (String existingCp : existingClassPaths) {
            if (!currentSet.contains(existingCp)) {
                jdbcTemplate.update("DELETE FROM " + TableConst.TOOL + " WHERE class_path = ? AND tool_type = 'BUILTIN'",
                        existingCp);
            }
        }

        // 3. 逐工具按租户作用域同步
        for (ToolInfoWrapper toolInfo : toolInfos) {
            syncSingleTool(toolInfo, allTenantIds, tenantCodeToId);
        }
    }

    /**
     * 同步单个内置工具：按作用域解析目标租户，diff 后 INSERT/UPDATE/DELETE
     */
    private void syncSingleTool(ToolInfoWrapper toolInfo, List<Long> allTenantIds,
                                Map<String, Long> tenantCodeToId) {
        ScopeType scopeType = toolInfo.getScopeType() != null ? toolInfo.getScopeType() : ScopeType.GLOBAL;
        List<Long> targetTenantIds = resolveTargetTenants(scopeType, toolInfo.getTenantCodes(),
                allTenantIds, tenantCodeToId);

        String classPath = toolInfo.getClassPath();

        // 查询该类路径在所有租户中的现有记录
        List<ExistingRecord> existingRecords = jdbcTemplate.query(
                "SELECT id, tenant_id FROM " + TableConst.TOOL
                        + " WHERE class_path = ? AND tool_type = 'BUILTIN'",
                (rs, rowNum) -> new ExistingRecord(rs.getLong("id"), rs.getLong("tenant_id")),
                classPath);

        Map<Long, Long> existingTenantToId = new HashMap<>();
        for (ExistingRecord r : existingRecords) {
            existingTenantToId.put(r.tenantId, r.id);
        }

        Set<Long> existingTenants = existingTenantToId.keySet();
        Set<Long> targetSet = new HashSet<>(targetTenantIds);

        // 需要新增的租户
        Set<Long> toAdd = new HashSet<>(targetSet);
        toAdd.removeAll(existingTenants);

        // 需要更新的租户（交集）
        Set<Long> toUpdate = new HashSet<>(targetSet);
        toUpdate.retainAll(existingTenants);

        // 需要删除的租户（作用域回收）
        Set<Long> toRemove = new HashSet<>(existingTenants);
        toRemove.removeAll(targetSet);

        // 执行变更
        for (Long tenantId : toAdd) {
            insertToolConfig(toolInfo, tenantId);
        }
        for (Long tenantId : toUpdate) {
            updateToolConfig(toolInfo, existingTenantToId.get(tenantId));
        }
        for (Long tenantId : toRemove) {
            jdbcTemplate.update("DELETE FROM " + TableConst.TOOL + " WHERE id = ?",
                    existingTenantToId.get(tenantId));
        }
    }

    /**
     * 解析目标租户列表
     */
    private List<Long> resolveTargetTenants(ScopeType scopeType, List<String> tenantCodes,
                                            List<Long> allTenantIds, Map<String, Long> tenantCodeToId) {
        if (scopeType == ScopeType.GLOBAL) {
            return allTenantIds;
        }
        // TENANT 作用域：按编码匹配
        List<Long> targetIds = new ArrayList<>();
        if (tenantCodes != null) {
            for (String code : tenantCodes) {
                Long id = tenantCodeToId.get(code);
                if (id != null) {
                    targetIds.add(id);
                }
            }
        }
        return targetIds;
    }

    /**
     * 插入内置工具到指定租户
     */
    private void insertToolConfig(ToolInfoWrapper toolInfo, Long tenantId) {
        long id = IdWorker.getId();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO " + TableConst.TOOL
                        + " (id, tenant_id, name, tool_id, description, category, tool_type,"
                        + " need_confirm, input_schema, class_path, enabled, scope_type, created_at, updated_at, ownership_type, visibility)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLATFORM', 'TENANT')",
                id,
                tenantId,
                toolInfo.getName(),
                toolInfo.getName(),
                toolInfo.getDescription(),
                "内置",
                ToolType.BUILTIN.name(),
                false,
                JsonUtils.toJsonStr(toolInfo.getParams()),
                toolInfo.getClassPath(),
                true,
                toolInfo.getScopeType().name(),
                now,
                now
        );
    }

    /**
     * 更新内置工具（刷新元数据，不改变租户归属）
     */
    private void updateToolConfig(ToolInfoWrapper toolInfo, Long recordId) {
        jdbcTemplate.update(
                "UPDATE " + TableConst.TOOL
                        + " SET name = ?, tool_id = ?, description = ?, input_schema = ?, scope_type = ?, updated_at = ?"
                        + " WHERE id = ?",
                toolInfo.getName(),
                toolInfo.getName(),
                toolInfo.getDescription(),
                JsonUtils.toJsonStr(toolInfo.getParams()),
                toolInfo.getScopeType().name(),
                LocalDateTime.now(),
                recordId
        );
    }

    /**
     * 查询所有启用的租户
     */
    private List<TenantRecord> getAllTenants() {
        return jdbcTemplate.query(
                "SELECT id, code FROM " + TableConst.TENANT + " WHERE enabled = 1",
                (rs, rowNum) -> new TenantRecord(rs.getLong("id"), rs.getString("code")));
    }

    /** 租户简要记录 */
    private record TenantRecord(Long id, String code) {}

    /** 已有记录（id, tenant_id） */
    private record ExistingRecord(Long id, Long tenantId) {}

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        List<Long> agentIds = agentToolService.getAgentIds(ids);
        Set<String> visibleIds = resourceVisibilityService.visibilityMap("AGENT", agentIds).keySet();
        getAgentDefinitions(agentIds).forEach(agentDefinition -> {
            if (visibleIds.contains(agentDefinition.getId().toString())) {
                names.add(agentDefinition.getName());
            }
        });

        return names;
    }

    @Override
    public List<String> listCategories() {
        return this.lambdaQuery()
                .select(ToolConfig::getCategory)
                .isNotNull(ToolConfig::getCategory)
                .groupBy(ToolConfig::getCategory)
                .list()
                .stream()
                .map(ToolConfig::getCategory)
                .filter(category -> category != null && !category.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public Boolean doUpdate(ToolConfig toolConfig) {
        ToolConfig current = getById(toolConfig.getId());
        ResourceOwnershipSupport.assertManageable(current, "修改");
        // 工具类型以持久记录为准，不能用客户端类型切换到内置工具更新分支。
        toolConfig.setToolType(current.getToolType());
        boolean result;
        if (current.getToolType() != ToolType.BUILTIN) {
            // 归属与可见性只能走「可见性」专用接口（同样只允许归属人/管理员），避免被 update 顺带篡改
            toolConfig.setOwnerId(null);
            toolConfig.setVisibility(null);
            result = updateById(toolConfig);
        } else {
            result = lambdaUpdate()
                    .eq(ToolConfig::getId, toolConfig.getId())
                    .set(toolConfig.getName() != null, ToolConfig::getName, toolConfig.getName())
                    .set(toolConfig.getCategory() != null, ToolConfig::getCategory, toolConfig.getCategory())
                    .set(toolConfig.getDescription() != null, ToolConfig::getDescription, toolConfig.getDescription())
                    .set(toolConfig.getNeedConfirm() != null, ToolConfig::getNeedConfirm, toolConfig.getNeedConfirm())
                    .set(toolConfig.getReturnDirect() != null, ToolConfig::getReturnDirect, toolConfig.getReturnDirect())
                    .set(toolConfig.getEnabled() != null, ToolConfig::getEnabled, toolConfig.getEnabled())
                    .set(toolConfig.getVersion() != null, ToolConfig::getVersion, toolConfig.getVersion())
                    .update();
        }
        publishAgentReregister(agentToolService.getAgentIds(List.of(toolConfig.getId())));
        return result;
    }

    private void publishAgentReregister(List<Long> agentIds) {
        agentIds.forEach(agentId ->
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(agentId)));
    }

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
            // 手动映射字段
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            agent.setDescription(rs.getString("description"));
            return agent;
        });
    }
}
