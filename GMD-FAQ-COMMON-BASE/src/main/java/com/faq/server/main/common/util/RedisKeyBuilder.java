package com.gmd.faq.server.main.common.util;

/**
 * Redis Key 构建工具类
 * 统一管理租户隔离的 Key 前缀
 *
 */
public class RedisKeyBuilder {

    private static final String PREFIX = "gmd-faq";

    /**
     * 构建租户隔离的 Key
     * 格式: gmd-faq:tenant:{tenantId}:{suffix}
     */
    public static String tenantKey(String suffix) {
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantId is not set in current context");
        }
        return PREFIX + ":tenant:" + tenantId + ":" + suffix;
    }

    /**
     * 构建全局共享的 Key（不区分租户）
     * 格式: gmd-faq:global:{suffix}
     */
    public static String globalKey(String suffix) {
        return PREFIX + ":global:" + suffix;
    }

    /**
     * 构建租户隔离的 Key，指定 tenantId（用于跨租户场景）
     * 格式: gmd-faq:tenant:{tenantId}:{suffix}
     */
    public static String tenantKey(Long tenantId, String suffix) {
        if (tenantId == null) {
            throw new IllegalArgumentException("TenantId must not be null");
        }
        return PREFIX + ":tenant:" + tenantId + ":" + suffix;
    }
}
