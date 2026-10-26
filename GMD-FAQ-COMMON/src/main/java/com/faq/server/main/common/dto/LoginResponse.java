package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.UserDetail;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 登录响应DTO
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 访问令牌
     */
    private String accessToken;

    /**
     * 到期时间（毫秒）
     */
    private Long accessTokenTTL;

    /**
     * 刷新令牌
     */
    private String refreshToken;

    /**
     * 到期时间（毫秒）
     */
    private Long refreshTokenTTL;

    /**
     * 用户详情
     */
    private UserDetail userDetail;

    /**
     * 是否需要选择租户
     */
    private Boolean needSelectTenant;

    /**
     * 可选择的租户列表（needSelectTenant = true 时返回）
     */
    private List<UserDetail.TenantInfo> tenants;

    /**
     * 是否必须先修改密码
     * <p>
     * 管理员新增用户、或管理员重置密码后为 true，前端登录成功后弹窗强制修改密码。
     */
    private Boolean mustChangePassword;
}
