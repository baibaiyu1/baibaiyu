package com.gmd.faq.server.main.hook.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.AgentHook;
import com.gmd.faq.server.main.common.entity.HookConfig;
import com.gmd.faq.server.main.common.enums.HookType;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.HookConfigWrapper;
import com.gmd.faq.server.main.hook.mapper.HookConfigMapper;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.hook.service.HookConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hook配置Service实现
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HookConfigServiceImpl extends ServiceImpl<HookConfigMapper, HookConfig> implements HookConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentHookService agentHookService;
    private final MessagePublisher messagePublisher;

    @Override
    public void SyncConfigToDatabase(List<HookConfigWrapper> configWrappers) {
        // 1. 获取所有租户（供 GLOBAL 作用域使用）
        List<TenantRecord> allTenants = getAllTenants();
        List<Long> allTenantIds = allTenants.stream().map(t -> t.id).toList();
        Map<String, Long> tenantCodeToId = new HashMap<>();
        for (TenantRecord t : allTenants) {
            if (t.code != null) {
                tenantCodeToId.put(t.code, t.id);
            }
        }

        // 2. 清理已从代码中移除的内置Hook（跨所有租户）
        List<String> currentClassPaths = configWrappers.stream()
                .map(HookConfigWrapper::getClassPath).toList();
        if (currentClassPaths.isEmpty()) {
            deleteBuiltinHooks("hook_type = 'BUILTIN' AND class_path IS NOT NULL");
            return;
        }
        List<String> existingClassPaths = jdbcTemplate.queryForList(
                "SELECT DISTINCT class_path FROM " + TableConst.HOOK
                        + " WHERE hook_type = 'BUILTIN' AND class_path IS NOT NULL",
                String.class);
        Set<String> currentSet = new HashSet<>(currentClassPaths);
        for (String existingCp : existingClassPaths) {
            if (!currentSet.contains(existingCp)) {
                deleteBuiltinHooks("class_path = ? AND hook_type = 'BUILTIN'", existingCp);
            }
        }

        // 3. 逐Hook按租户作用域同步
        for (HookConfigWrapper configWrapper : configWrappers) {
            syncSingleHook(configWrapper, allTenantIds, tenantCodeToId);
        }
    }

    /**
     * 同步单个内置Hook：按作用域解析目标租户，diff 后 INSERT/UPDATE/DELETE
     */
    private void syncSingleHook(HookConfigWrapper configWrapper, List<Long> allTenantIds,
                                Map<String, Long> tenantCodeToId) {
        ScopeType scopeType = configWrapper.getScopeType() != null
                ? configWrapper.getScopeType() : ScopeType.GLOBAL;
        List<Long> targetTenantIds = resolveTargetTenants(scopeType, configWrapper.getTenantCodes(),
                allTenantIds, tenantCodeToId);

        String classPath = configWrapper.getClassPath();

        // 查询该类路径在所有租户中的现有记录
        List<ExistingRecord> existingRecords = jdbcTemplate.query(
                "SELECT id, tenant_id FROM " + TableConst.HOOK
                        + " WHERE class_path = ? AND hook_type = 'BUILTIN'",
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
            insertHookConfig(configWrapper, tenantId);
        }
        for (Long tenantId : toUpdate) {
            updateHookConfig(configWrapper, existingTenantToId.get(tenantId));
        }
        for (Long tenantId : toRemove) {
            deleteBuiltinHooks("id = ?", existingTenantToId.get(tenantId));
        }

        // 兜底清理历史遗留的悬挂绑定：Hook 已不存在、绑定仍指向旧 id 的记录。
        // 同步逻辑会删除旧记录并重建为新 id；若不清理绑定，前端打开该 Hook 详情
        // 会因查不到记录而报错。
        jdbcTemplate.update("DELETE ah FROM " + TableConst.AGENT_HOOKS + " ah"
                + " LEFT JOIN " + TableConst.HOOK + " hc ON hc.id = ah.hook_config_id"
                + " WHERE hc.id IS NULL");
    }

    /**
     * 删除内置Hook记录，并同步清理智能体绑定（agent_hooks），避免遗留悬挂引用。
     * 走 JdbcTemplate 以跨租户生效，与启动期同步的跨租户语义一致。
     *
     * @param whereClause 删除条件（仅由本类内部常量拼接，不含外部输入）
     * @param args        条件参数
     */
    private void deleteBuiltinHooks(String whereClause, Object... args) {
        List<Long> hookIds = jdbcTemplate.queryForList(
                "SELECT id FROM " + TableConst.HOOK + " WHERE " + whereClause, Long.class, args);
        jdbcTemplate.update("DELETE FROM " + TableConst.HOOK + " WHERE " + whereClause, args);
        if (hookIds.isEmpty()) {
            return;
        }
        String placeholders = hookIds.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM " + TableConst.AGENT_HOOKS
                + " WHERE hook_config_id IN (" + placeholders + ")", hookIds.toArray());
    }

    /**
     * 解析目标租户列表
     */
    private List<Long> resolveTargetTenants(ScopeType scopeType, List<String> tenantCodes,
                                            List<Long> allTenantIds, Map<String, Long> tenantCodeToId) {
        if (scopeType == ScopeType.GLOBAL) {
            return allTenantIds;
        }
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
     * 插入内置Hook到指定租户
     */
    private void insertHookConfig(HookConfigWrapper configWrapper, Long tenantId) {
        long id = IdWorker.getId();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO " + TableConst.HOOK
                        + " (id, tenant_id, name, hook_type, description, class_path, enabled, priority,"
                        + " scope_type, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                tenantId,
                configWrapper.getName(),
                HookType.BUILTIN.name(),
                configWrapper.getDescription(),
                configWrapper.getClassPath(),
                true,
                1,
                configWrapper.getScopeType().name(),
                now,
                now
        );
    }

    /**
     * 更新内置Hook（刷新元数据，不改变租户归属）
     */
    private void updateHookConfig(HookConfigWrapper configWrapper, Long recordId) {
        jdbcTemplate.update(
                "UPDATE " + TableConst.HOOK
                        + " SET name = ?, description = ?, scope_type = ?, updated_at = ?"
                        + " WHERE id = ?",
                configWrapper.getName(),
                configWrapper.getDescription(),
                configWrapper.getScopeType().name(),
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
        getAgentDefinitions(agentHookService.getAgentIds(ids)).forEach(agentDefinition -> {
            names.add(agentDefinition.getName());
        });

        return names;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        // 删除前先获取关联的智能体ID，以便后续触发重新注册
        List<Long> agentIds = agentHookService.getAgentIds(ids);
        removeByIds(ids);
        boolean result = agentHookService.remove(new LambdaQueryWrapper<AgentHook>().in(AgentHook::getHookConfigId, ids));
        publishAgentReregister(agentIds);
        return result;
    }

    @Override
    public boolean doUpdate(HookConfig entity) {
        boolean result = updateById(entity);
        publishAgentReregister(agentHookService.getAgentIds(List.of(entity.getId())));
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
