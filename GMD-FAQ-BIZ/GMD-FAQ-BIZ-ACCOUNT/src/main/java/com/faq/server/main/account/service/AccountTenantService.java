package com.gmd.faq.server.main.account.service;

import com.gmd.faq.server.main.common.entity.AccountTenant;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 账号-租户关联服务
 * <p>
 * 「部门即组织」后，组织归属由 {@code account.dept_id} 表达，本服务**不再参与成员管理**：
 * 只剩租户开通（{@code TenantServiceImpl.createTenant} / {@code addMember}）在写这张历史表。
 * 原 {@code listByTenantId}（成员列表）与 {@code updateRole}（成员改角色）已随
 * 「组织管理 - 成员管理」一起删除，不要在新代码里使用 {@code account_tenant.role}。
 *
 */
public interface AccountTenantService extends IService<AccountTenant> {

    /**
     * 查询账号所属的所有组织（仅租户开通流程使用，业务代码请用 {@code account.dept_id}）
     */
    List<AccountTenant> listByAccountId(Long accountId);

    /**
     * 查询账号在指定租户中的关联
     */
    AccountTenant getByAccountAndTenant(Long accountId, Long tenantId);
}
