package com.gmd.faq.server.main.account.service.impl;

import com.gmd.faq.server.main.account.mapper.AccountTenantMapper;
import com.gmd.faq.server.main.account.service.AccountTenantService;
import com.gmd.faq.server.main.common.entity.AccountTenant;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号-租户关联服务实现
 * <p>
 * 「部门即组织」后只剩租户开通流程在用这张历史表，组织归属一律看 {@code account.dept_id}。
 *
 */
@Service
public class AccountTenantServiceImpl extends ServiceImpl<AccountTenantMapper, AccountTenant> implements AccountTenantService {

    @Override
    public List<AccountTenant> listByAccountId(Long accountId) {
        return lambdaQuery()
                .eq(AccountTenant::getAccountId, accountId)
                .eq(AccountTenant::getEnabled, true)
                .list();
    }

    @Override
    public AccountTenant getByAccountAndTenant(Long accountId, Long tenantId) {
        return lambdaQuery()
                .eq(AccountTenant::getAccountId, accountId)
                .eq(AccountTenant::getTenantId, tenantId)
                .eq(AccountTenant::getEnabled, true)
                .one();
    }
}
