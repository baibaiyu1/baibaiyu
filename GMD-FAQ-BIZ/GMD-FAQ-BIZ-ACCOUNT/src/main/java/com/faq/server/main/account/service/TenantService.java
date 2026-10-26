package com.gmd.faq.server.main.account.service;

import com.gmd.faq.server.main.common.dto.TenantSettingsDTO;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 租户服务
 *
 */
public interface TenantService extends IService<Tenant> {

    /**
     * 根据编码查询租户
     */
    Tenant getByCode(String code);

    /**
     * 创建租户并初始化基础数据
     */
    Tenant createTenant(Tenant tenant, Long creatorAccountId);

    /**
     * 更新租户基本设置
     */
    Tenant updateSettings(Long tenantId, TenantSettingsDTO settings);

    /**
     * 直接添加成员到租户
     */
    void addMember(Long tenantId, Long accountId, String role);
}
