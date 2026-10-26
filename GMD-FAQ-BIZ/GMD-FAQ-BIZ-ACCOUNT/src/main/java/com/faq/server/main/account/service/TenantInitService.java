package com.gmd.faq.server.main.account.service;

/**
 * 租户初始化服务 — 从默认租户拷贝内置种子数据到新租户
 *
 */
public interface TenantInitService {

    /**
     * 为新租户初始化种子数据（params、内置 tools、内置 MCP 等）
     *
     * @param newTenantId 新租户ID
     */
    void initTenantData(Long newTenantId);
}
