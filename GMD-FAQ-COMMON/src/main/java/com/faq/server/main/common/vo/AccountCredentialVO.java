package com.gmd.faq.server.main.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账号初始/重置密码结果VO
 * <p>
 * 服务端只保存加盐 MD5 摘要，明文密码仅在本接口响应中返回一次，之后无法再次获取。
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCredentialVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 账号ID */
    private Long id;

    /** 登录账号 */
    private String username;

    /** 昵称 */
    private String nickname;

    /**
     * 明文密码（仅本次返回）
     * <p>
     * 管理员手动指定密码时为管理员输入的值，随机生成时为服务端生成的随机串。
     */
    private String password;

    /** 密码是否由服务端随机生成 */
    private Boolean generated;

    /** 用户首次登录后是否必须修改密码 */
    private Boolean mustChangePassword;
}
