package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员新增用户请求DTO
 * <p>
 * 系统已关闭自助注册：账号只能由管理员创建。「部门即组织」后，
 * {@link #deptId} 决定新账号归属的组织（部门）；允许留空（无组织账号），
 * 留空时该账号登录后仅能看见自己的资源。新账号首次登录后需强制修改密码。
 *
 */
@Data
public class AccountCreateRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 用户名（登录账号）
     */
    private String username;

    /**
     * 邮箱（可选，填写时参与登录校验，需全局唯一）
     */
    private String email;

    /**
     * 归属部门（组织）ID；留空表示不归属任何组织
     */
    private Long deptId;

    /**
     * 初始密码（可选）
     * <p>
     * 留空时由服务端生成随机密码，并在响应中一次性返回。
     */
    private String password;
}
