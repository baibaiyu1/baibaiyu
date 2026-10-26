package com.gmd.faq.server.main.account.service.impl;

import com.gmd.faq.server.main.account.mapper.TenantMapper;
import com.gmd.faq.server.main.account.service.AccountTenantService;
import com.gmd.faq.server.main.account.service.TenantInitService;
import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.common.dto.TenantSettingsDTO;
import com.gmd.faq.server.main.common.entity.AccountTenant;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.entity.RoleAccount;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.gmd.faq.server.main.common.enums.TenantRole;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.system.mapper.RoleAccountMapper;
import com.gmd.faq.server.main.system.service.RoleService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户服务实现
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    private final AccountTenantService accountTenantService;
    private final TenantInitService tenantInitService;
    private final RoleService roleService;
    private final RoleAccountMapper roleAccountMapper;

    @Override
    public Tenant getByCode(String code) {
        return lambdaQuery().eq(Tenant::getCode, code).one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tenant createTenant(Tenant tenant, Long creatorAccountId) {
        // 保存租户
        save(tenant);

        // 将创建者添加为租户管理员
        AccountTenant membership = new AccountTenant();
        membership.setId(IdWorker.getId());
        membership.setAccountId(creatorAccountId);
        membership.setTenantId(tenant.getId());
        membership.setRole(TenantRole.TENANT_OWNER);
        accountTenantService.save(membership);

        // 初始化种子数据（params、内置 tools/MCP/skills、系统设置内置部门与角色等）
        tenantInitService.initTenantData(tenant.getId());

        // 创建者默认获得本租户「超级管理员」角色（系统设置 - 角色配置中的用户分配）
        assignBuiltinRoleToCreator(tenant.getId(), creatorAccountId);

        return tenant;
    }

    /**
     * 将新租户的创建者分配到该租户的内置「超级管理员」角色。
     * <p>
     * 内置角色由 {@link TenantInitService#initTenantData(Long)} 创建，因此本方法必须在其之后调用。
     * 需要切换到新租户的上下文，租户拦截器才会把 role_account 写到正确的 tenant_id 上。
     */
    private void assignBuiltinRoleToCreator(Long tenantId, Long accountId) {
        if (accountId == null) {
            return;
        }
        TenantUtils.setCurrentTenant(tenantId, null);
        try {
            Role superAdmin = roleService.lambdaQuery()
                    .eq(Role::getTenantId, tenantId)
                    .eq(Role::getCode, "superadmin")
                    .one();
            if (superAdmin == null) {
                log.warn("租户 {} 未找到内置超级管理员角色，跳过创建者角色分配", tenantId);
                return;
            }
            RoleAccount relation = new RoleAccount();
            relation.setId(IdWorker.getId());
            relation.setRoleId(superAdmin.getId());
            relation.setAccountId(accountId);
            relation.setTenantId(tenantId);
            roleAccountMapper.insert(relation);
        } finally {
            TenantUtils.clear();
        }
    }

    @Override
    public Tenant updateSettings(Long tenantId, TenantSettingsDTO settings) {
        Tenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        if (!FuncUtils.isEmpty(settings.getName())) tenant.setName(settings.getName());
        if (settings.getDescription() != null) tenant.setDescription(settings.getDescription());
        if (settings.getContactName() != null) tenant.setContactName(settings.getContactName());
        if (settings.getContactEmail() != null) tenant.setContactEmail(settings.getContactEmail());

        updateById(tenant);
        return tenant;
    }

    @Override
    public void addMember(Long tenantId, Long accountId, String role) {
        // 检查是否已是成员
        AccountTenant existing = accountTenantService.getByAccountAndTenant(accountId, tenantId);
        if (existing != null) {
            throw new BusinessException("该用户已是该租户的成员");
        }

        AccountTenant membership = new AccountTenant();
        membership.setId(IdWorker.getId());
        membership.setAccountId(accountId);
        membership.setTenantId(tenantId);
        membership.setRole(TenantRole.valueOf(role));
        accountTenantService.save(membership);
    }
}
