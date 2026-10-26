package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录请求DTO
 *
 */
@Data
public class LoginRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户名或邮箱
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 租户ID（多租户用户选择租户登录时携带）
     */
    private Long tenantId;
}
