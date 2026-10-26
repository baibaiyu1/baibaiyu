package com.gmd.faq.server.main.common.util;

import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collection;
import java.util.List;

/**
 * 描述：用户工具类
 * <p>
 * 权限判定一律走 {@link #hasPerm(String)} / {@link #isAdmin()}（单轨权限）：
 * 口径是「账号在当前租户下的 角色 → 菜单/按钮 perms」，由 {@code AuthInterceptor}
 * 在每个请求上解析并挂到 {@code UserDetail.perms}；不再参考 {@code account_tenant.role} 旧枚举。
 *
 **/
public class UserUtils {
    private static final ThreadLocal<UserDetail> BACKGROUND_USER = new ThreadLocal<>();

    /** 仅由服务端后台入口设置；异步边界通过 AgentContextPropagation 传播。 */
    public static UserDetail getBackgroundUserDetail() { return BACKGROUND_USER.get(); }
    public static void setBackgroundUserDetail(UserDetail detail) {
        if (detail == null) BACKGROUND_USER.remove(); else BACKGROUND_USER.set(detail);
    }
    public static <T> T withUserDetail(UserDetail detail, java.util.function.Supplier<T> action) {
        UserDetail previous = BACKGROUND_USER.get();
        try { setBackgroundUserDetail(detail); return action.get(); }
        finally { setBackgroundUserDetail(previous); }
    }

    /**
     * 平台管理员权限点（由 superadmin / system_admin 内置角色授予）
     */
    public static final String PERM_ADMIN = "system:admin";
    public static final String AUTH_ACCOUNT = "ACCOUNT";
    public static final String AUTH_SK = "SK";
    public static final String AUTH_CHAT_KEY = "CHAT_KEY";

    public static boolean isChatKey() {
        UserDetail detail = getUserDetail();
        return detail != null && AUTH_CHAT_KEY.equals(detail.getAuthType());
    }

    public static boolean isSk() {
        UserDetail detail = getUserDetail();
        return detail != null && AUTH_SK.equals(detail.getAuthType());
    }

    public static Long getBoundAgentId() {
        UserDetail detail = getUserDetail();
        return detail == null ? null : detail.getBoundAgentId();
    }

    public static String getBoundAgentCode() {
        UserDetail detail = getUserDetail();
        return detail == null ? null : detail.getBoundAgentCode();
    }

    public static String getChatKeyId() {
        UserDetail detail = getUserDetail();
        return detail == null ? null : detail.getChatKeyId();
    }

    public static Long getSkId() {
        UserDetail detail = getUserDetail();
        return detail == null ? null : detail.getSkId();
    }

    public static Long getResourceAccountId() {
        UserDetail detail = getUserDetail();
        if (detail == null || AUTH_CHAT_KEY.equals(detail.getAuthType())) {
            return null;
        }
        return AUTH_SK.equals(detail.getAuthType()) ? detail.getPrincipalAccountId() : detail.getId();
    }

    public static Long getId() {
        UserDetail userDetail = getUserDetail();
        if (userDetail == null) {
            return 0L;
        }
        return userDetail.getId();
    }

    public static String getAccount() {
        UserDetail userDetail = getUserDetail();
        if (userDetail == null) {
            return null;
        }
        return userDetail.getUsername();
    }

    public static Long getTenantId() {
        UserDetail userDetail = getUserDetail();
        return userDetail != null ? userDetail.getTenantId() : null;
    }

    public static String getTenantCode() {
        UserDetail userDetail = getUserDetail();
        return userDetail != null ? userDetail.getTenantCode() : null;
    }

    public static UserDetail getUserDetail() {
        try {
            HttpServletRequest request = WebUtils.getRequest();
            if (request == null) {
                return BACKGROUND_USER.get();
            }
            UserDetail detail = (UserDetail) request.getAttribute(SysConst.USER_DETAIL);
            return detail != null ? detail : BACKGROUND_USER.get();
        } catch (IllegalStateException e) {
            // 异步线程或请求生命周期结束后，request 可能已回收，此时按匿名上下文处理。
            return BACKGROUND_USER.get();
        }
    }

    /**
     * 当前登录账号在当前租户下的权限点集合
     *
     * @return 权限点列表；无登录上下文时返回空列表
     */
    public static List<String> getPerms() {
        UserDetail userDetail = getUserDetail();
        if (userDetail == null || userDetail.getPerms() == null) {
            return List.of();
        }
        return userDetail.getPerms();
    }

    /**
     * 是否拥有指定权限点
     *
     * @param perm 权限点（如 {@code agent:definition:edit}）
     */
    public static boolean hasPerm(String perm) {
        if (perm == null || perm.isBlank()) {
            return false;
        }
        return getPerms().contains(perm);
    }

    /**
     * 是否拥有任意一个指定权限点
     *
     * @param perms 权限点集合
     */
    public static boolean hasAnyPerm(String... perms) {
        if (perms == null || perms.length == 0) {
            return false;
        }
        return hasAnyPerm(List.of(perms));
    }

    /**
     * 是否拥有任意一个指定权限点
     *
     * @param perms 权限点集合
     */
    public static boolean hasAnyPerm(Collection<String> perms) {
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        List<String> owned = getPerms();
        if (owned.isEmpty()) {
            return false;
        }
        for (String perm : perms) {
            if (owned.contains(perm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前登录用户是否为平台管理员
     * <p>
     * 单轨口径：是否拥有 {@link #PERM_ADMIN} 权限点；
     * 该权限点只授予内置角色 superadmin / system_admin（见 V14 迁移），
     * 旧枚举 TENANT_ADMIN/TENANT_OWNER 已不参与判定。
     */
    public static boolean isAdmin() {
        return hasPerm(PERM_ADMIN);
    }
}
