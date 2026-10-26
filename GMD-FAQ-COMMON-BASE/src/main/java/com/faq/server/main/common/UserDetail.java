package com.gmd.faq.server.main.common;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 描述：用户详情
 *
 **/
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserDetail implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private Long id;
    // 昵称
    private String name;
    // 账号
    private String username;
    // 邮箱
    private String email;
    // 当前会话选中的租户ID（内部实现细节，前端不再感知「组织/租户」，见 AGENTS.md）
    private Long tenantId;
    // 当前租户编码
    private String tenantCode;
    // 当前租户内的角色（旧枚举，已废弃，仅保留字段以兼容历史 JWT 反序列化）
    private String tenantRole;
    // 当前租户名称
    private String tenantName;
    // 用户可访问的租户列表（多组织已下线，恒为空）
    private List<TenantInfo> tenants;
    /**
     * 当前租户下的权限点集合（单轨权限）
     * <p>
     * 不进 JWT：由 {@code AuthInterceptor} 在每个请求上解析后写入当前请求的 UserDetail 实例，
     * 业务代码通过 {@code UserUtils.hasPerm(...)} 读取。
     */
    private List<String> perms;

    /**
     * 当前租户下的数据权限范围（多角色取最宽，取值见 {@code DataScope}）
     * <p>
     * 同样由 {@code AuthInterceptor} 每请求解析；资源可见性过滤会叠加该范围。
     */
    private String dataScope;

    /**
     * 当前账号所属部门（组织）ID（可为空）
     * <p>
     * 「部门即组织」后这就是账号的组织归属：为空表示未归属任何组织。
     * 既用于 DEPT / DEPT_AND_CHILD 数据范围过滤，也决定账号能拿到哪些部门级角色。
     */
    private Long deptId;

    /** ACCOUNT / SK / CHAT_KEY; set by the authentication interceptor. */
    private String authType;
    /** Agent bound to an external ChatKey visitor. */
    private Long boundAgentId;
    private String boundAgentCode;
    /** Current share key identifier, set from the signed JWT id. */
    private String chatKeyId;
    /** Secret key identifier, set from the signed JWT id. */
    private Long skId;
    /** Real account that owns an SK; absent for external visitors. */
    private Long principalAccountId;

    /**
     * 当前账号所属部门（组织）名称（由 AuthInterceptor 每请求补齐，便于前端展示）
     */
    private String deptName;

    /**
     * 租户简要信息（用于切换器展示）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantInfo implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long tenantId;
        private String tenantCode;
        private String tenantName;
        private String role;
    }
}
