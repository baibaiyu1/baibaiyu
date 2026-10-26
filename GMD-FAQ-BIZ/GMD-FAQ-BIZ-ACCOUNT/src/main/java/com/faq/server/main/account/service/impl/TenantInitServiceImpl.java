package com.gmd.faq.server.main.account.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.account.mapper.TenantMapper;
import com.gmd.faq.server.main.account.service.TenantInitService;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.enums.HookType;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.common.enums.ResourceVisibility;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.hook.mapper.HookConfigMapper;
import com.gmd.faq.server.main.params.mapper.ParamsMapper;
import com.gmd.faq.server.main.system.mapper.DepartmentMapper;
import com.gmd.faq.server.main.system.mapper.MenuMapper;
import com.gmd.faq.server.main.system.mapper.RoleMapper;
import com.gmd.faq.server.main.system.mapper.RoleMenuMapper;
import com.gmd.faq.server.main.tool.mapper.ToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 租户初始化服务实现 — 从默认租户（id=1）拷贝内置种子数据到新租户
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantInitServiceImpl implements TenantInitService {

    private static final Long DEFAULT_TENANT_ID = 1L;
    private static final String DEFAULT_TENANT_CODE = "default";

    /**
     * 仅管理员可授予的权限点（editor/viewer 内置角色不包含）；与 V13 迁移中的授权口径一致
     */
    private static final Set<String> ADMIN_ONLY_PERMS = Set.of(
            "resource:visibility:manage",
            "approval:flow:config",
            "approval:request:approve");

    /**
     * 只读角色可看到的目录节点权限
     */
    private static final Set<String> READ_ONLY_DIR_PERMS = Set.of("space", "dev", "resource");

    private final ParamsMapper paramsMapper;
    private final ToolMapper toolMapper;
    private final HookConfigMapper hookConfigMapper;
    private final DepartmentMapper departmentMapper;
    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;
    private final TenantMapper tenantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initTenantData(Long newTenantId) {
        // 显式设置当前租户为默认租户（id=1），确保 TenantLine 拦截器生成正确的 tenant_id = 1 条件，而非 tenant_id = null。
        TenantUtils.setCurrentTenant(DEFAULT_TENANT_ID, DEFAULT_TENANT_CODE);
        try {
            log.info("开始为新租户 {} 初始化种子数据...", newTenantId);

            copyParams(newTenantId);
            copyToolConfigs(newTenantId);
            copyHookConfigs(newTenantId);

            log.info("租户 {} 种子数据初始化完成", newTenantId);
        } finally {
            TenantUtils.clear();
        }

        // 系统设置（部门 / 角色）按新租户独立创建，同样需要在正确的租户上下文下写入
        TenantUtils.setCurrentTenant(newTenantId, null);
        try {
            initRootDepartment(newTenantId);
            initBuiltinRoles(newTenantId);
        } finally {
            TenantUtils.clear();
        }
    }

    // ---- 系统设置：根部门 ----

    /**
     * 为新租户创建根部门（名称取租户名）。
     * <p>
     * 与 V9 迁移脚本对存量租户的补齐逻辑保持一致：根部门名 = 租户名，parent_id = 0。
     */
    private void initRootDepartment(Long newTenantId) {
        Long exists = departmentMapper.selectCount(
                Wrappers.<Department>lambdaQuery()
                        .eq(Department::getTenantId, newTenantId)
                        .eq(Department::getParentId, 0L));
        if (exists != null && exists > 0) {
            return;
        }
        Tenant tenant = tenantMapper.selectById(newTenantId);
        String name = tenant != null && tenant.getName() != null && !tenant.getName().isBlank()
                ? tenant.getName()
                : "默认部门";

        Department department = new Department();
        department.setId(IdWorker.getId());
        department.setParentId(0L);
        department.setName(name);
        department.setSortOrder(0);
        department.setRemark("系统初始化根部门");
        department.setEnabled(true);
        department.setTenantId(newTenantId);
        departmentMapper.insert(department);
        log.info("为新租户 {} 创建根部门：{}", newTenantId, name);
    }

    // ---- 系统设置：内置角色（超级管理员 / 系统超级管理员 / 业务编辑者 / 业务查看者）----
    // 重要：本方法与迁移 V13__business_perms_and_builtin_roles.sql 的内置角色与授权口径必须保持一致——
    //      存量租户由 V13 建角色并授权，新租户由这里创建；两处规则一旦漂移，新旧租户的权限就会不一致。
    //      （切换单轨权限后，前端与后端都只按「角色 → 菜单 perms」判定，不再看 account_tenant.role。）

    private void initBuiltinRoles(Long newTenantId) {
        List<Menu> menus = menuMapper.selectList(null);
        if (menus.isEmpty()) {
            log.warn("菜单表为空，跳过内置角色初始化（请确认 V9/V13 迁移已执行）");
            return;
        }

        List<BuiltinRoleDef> definitions = List.of(
                new BuiltinRoleDef("超级管理员", "superadmin", 1, DataScope.ALL,
                        "系统内置角色，拥有全部权限", menu -> true),
                new BuiltinRoleDef("系统超级管理员", "system_admin", 2, DataScope.ALL,
                        "系统内置角色，拥有全部权限", menu -> true),
                // 与 V17 的存量租户角色范围保持一致；资源可见性另按部门与授权判断。
                new BuiltinRoleDef("业务编辑者", "editor", 3, DataScope.DEPT_AND_CHILD,
                        "系统内置角色，拥有业务域全部权限（不含系统设置与运维）",
                        TenantInitServiceImpl::isBusinessMenu),
                new BuiltinRoleDef("业务查看者", "viewer", 4, DataScope.DEPT_AND_CHILD,
                        "系统内置角色，仅业务域只读权限",
                        TenantInitServiceImpl::isReadOnlyMenu)
        );

        for (BuiltinRoleDef definition : definitions) {
            Long exists = roleMapper.selectCount(
                    Wrappers.<Role>lambdaQuery()
                            .eq(Role::getTenantId, newTenantId)
                            .eq(Role::getCode, definition.code()));
            if (exists != null && exists > 0) {
                continue;
            }

            Role role = new Role();
            role.setId(IdWorker.getId());
            role.setName(definition.name());
            role.setCode(definition.code());
            role.setSortOrder(definition.sortOrder());
            role.setDataScope(definition.dataScope().name());
            role.setBuiltin(true);
            role.setRemark(definition.remark());
            role.setEnabled(true);
            role.setTenantId(newTenantId);
            roleMapper.insert(role);

            int granted = 0;
            for (Menu menu : menus) {
                if (!definition.menuFilter().test(menu)) {
                    continue;
                }
                RoleMenu relation = new RoleMenu();
                relation.setId(IdWorker.getId());
                relation.setRoleId(role.getId());
                relation.setMenuId(menu.getId());
                relation.setTenantId(newTenantId);
                roleMenuMapper.insert(relation);
                granted++;
            }
            log.info("为新租户 {} 创建内置角色：{}（授权菜单 {} 项）", newTenantId, role.getName(), granted);
        }
    }

    /**
     * 业务域菜单：排除系统设置 / 租户管理 / 监控 / 运维，以及可见性管理与审批处理（与 V13 授权口径一致）
     */
    private static boolean isBusinessMenu(Menu menu) {
        String perms = menu.getPerms();
        if (perms == null || perms.isBlank()) {
            return true;
        }
        if (isSystemScope(perms)) {
            return false;
        }
        return !ADMIN_ONLY_PERMS.contains(perms);
    }

    /**
     * 只读菜单：仅「列表 / 查看」类权限与三个业务目录（与 V13 中 viewer 的授权口径一致）
     */
    private static boolean isReadOnlyMenu(Menu menu) {
        String perms = menu.getPerms();
        if (perms == null || perms.isBlank() || isSystemScope(perms)) {
            return false;
        }
        return perms.endsWith(":list") || perms.endsWith(":view") || READ_ONLY_DIR_PERMS.contains(perms);
    }

    private static boolean isSystemScope(String perms) {
        return perms.startsWith("system:") || perms.startsWith("tenant:")
                || perms.startsWith("monitor:") || perms.startsWith("ops:");
    }

    /**
     * 内置角色定义（名称、编码、排序、数据范围、备注、可授权的菜单过滤规则）
     */
    private record BuiltinRoleDef(String name,
                                  String code,
                                  int sortOrder,
                                  DataScope dataScope,
                                  String remark,
                                  Predicate<Menu> menuFilter) {
    }

    // ---- Params: 全局默认 → 租户级 ----

    private void copyParams(Long newTenantId) {
        List<Params> sourceList = paramsMapper.selectList(
                Wrappers.<Params>lambdaQuery().eq(Params::getTenantId, DEFAULT_TENANT_ID));
        if (sourceList.isEmpty()) {
            log.warn("未找到全局默认参数，跳过");
            return;
        }
        for (Params src : sourceList) {
            Params copy = new Params();
            BeanUtils.copyProperties(src, copy);
            copy.setId(IdWorker.getId());
            copy.setTenantId(newTenantId);
            paramsMapper.insert(copy);
        }
        log.info("复制了 {} 条 params", sourceList.size());
    }

    // ---- 内置Tool/Hook（仅复制 GLOBAL 作用域）----
    // 注：启动时 ToolsSyncToDatabase / HooksSyncToDatabase 已按作用域同步到所有租户。
    // 此处为新创建租户补充全局作用域的内置数据：从默认租户(id=1)复制 scope_type='GLOBAL' 的记录。

    private void copyToolConfigs(Long newTenantId) {
        List<ToolConfig> sourceList = toolMapper.selectList(
                Wrappers.<ToolConfig>lambdaQuery()
                        .eq(ToolConfig::getTenantId, DEFAULT_TENANT_ID)
                        .eq(ToolConfig::getToolType, ToolType.BUILTIN)
                        .eq(ToolConfig::getScopeType, ScopeType.GLOBAL));
        if (sourceList.isEmpty()) {
            log.warn("未找到默认租户的全局内置工具配置，跳过");
            return;
        }
        for (ToolConfig src : sourceList) {
            ToolConfig copy = new ToolConfig();
            BeanUtils.copyProperties(src, copy);
            copy.setId(IdWorker.getId());
            copy.setTenantId(newTenantId);
            copy.setCreatedAt(null);
            copy.setUpdatedAt(null);
            copy.setCreatedBy(null);
            copy.setUpdatedBy(null);
            copy.setOwnerId(null);
            copy.setOwnershipType("PLATFORM");
            copy.setOwningDeptId(null);
            copy.setMaintainerId(null);
            copy.setResourceDeptId(null);
            copy.setVisibility(ResourceVisibility.TENANT.name());
            toolMapper.insert(copy);
        }
        log.info("复制了 {} 个 Tool Config", sourceList.size());
    }

    private void copyHookConfigs(Long newTenantId) {
        List<HookConfig> sourceList = hookConfigMapper.selectList(
                Wrappers.<HookConfig>lambdaQuery()
                        .eq(HookConfig::getTenantId, DEFAULT_TENANT_ID)
                        .eq(HookConfig::getHookType, HookType.BUILTIN)
                        .eq(HookConfig::getScopeType, ScopeType.GLOBAL));
        if (sourceList.isEmpty()) {
            log.warn("未找到默认租户的全局内置Hook配置，跳过");
            return;
        }
        for (HookConfig src : sourceList) {
            HookConfig copy = new HookConfig();
            BeanUtils.copyProperties(src, copy);
            copy.setId(IdWorker.getId());
            copy.setTenantId(newTenantId);
            copy.setCreatedAt(null);
            copy.setUpdatedAt(null);
            copy.setCreatedBy(null);
            copy.setUpdatedBy(null);
            hookConfigMapper.insert(copy);
        }
        log.info("复制了 {} 个 Hook Config", sourceList.size());
    }
}
