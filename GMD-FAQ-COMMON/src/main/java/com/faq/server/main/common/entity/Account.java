package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号表
 *
 */
@Getter
@Setter
@TableName(value = TableConst.ACCOUNT)
public class Account extends BaseEntity {

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 上次登录的租户ID（用于登录时自动选择）
     */
    private Long lastTenantId;

    /**
     * 记住上次登录
     */
    private Boolean rememberLastTenant;

    /**
     * 所属部门ID（可空，见 gmd_faq_department）
     */
    private Long deptId;

    /**
     * 是否必须先修改密码
     * <p>
     * 管理员新增用户、或管理员重置密码后置为 true，用户下次登录会被强制弹窗修改密码；
     * 用户自行修改密码成功后置回 false。
     */
    private Boolean mustChangePassword;
}
