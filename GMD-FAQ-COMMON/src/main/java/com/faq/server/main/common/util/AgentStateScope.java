package com.gmd.faq.server.main.common.util;

/** AgentScope 2.0 的状态命名空间；身份必须来自服务端会话或登录上下文。 */
public final class AgentStateScope {
    private AgentStateScope() {}

    public static String userId(Long tenantId, Long accountId) {
        if (tenantId == null || accountId == null) {
            throw new IllegalArgumentException("会话租户和账号不能为空");
        }
        return "tenant_" + tenantId + "_user_" + accountId;
    }

    public static String sessionKey(Long tenantId, Long accountId, String sessionId) {
        return userId(tenantId, accountId) + ":" + sessionId;
    }
}
