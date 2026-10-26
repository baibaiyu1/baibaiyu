package com.gmd.faq.server.main.common.util;

/**
 * 租户上下文工具类
 * 基于 ThreadLocal 存储当前请求的租户上下文
 *
 */
public class TenantUtils {

    private static final ThreadLocal<Long> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT_CODE = new ThreadLocal<>();

    public static Long getCurrentTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    public static String getCurrentTenantCode() {
        return CURRENT_TENANT_CODE.get();
    }

    public static void setCurrentTenant(Long tenantId, String tenantCode) {
        CURRENT_TENANT_ID.set(tenantId);
        CURRENT_TENANT_CODE.set(tenantCode);
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
        CURRENT_TENANT_CODE.remove();
    }
}
