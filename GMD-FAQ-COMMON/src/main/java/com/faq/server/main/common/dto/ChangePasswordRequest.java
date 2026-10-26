package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改密码请求DTO
 *
 */
@Data
public class ChangePasswordRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 旧密码（明文密码的 MD5）
     * <p>
     * 用户自助改密时必填；「首次登录强制改密」场景可省略 —— 此时账号上带着
     * {@code must_change_password = 1}，用户刚用管理员分配的密码完成登录，
     * 服务端不再要求重复输入（见 {@code AccountServiceImpl.changePassword}）。
     */
    private String oldPassword;

    /**
     * 新密码（明文密码的 MD5）
     */
    private String newPassword;
}
