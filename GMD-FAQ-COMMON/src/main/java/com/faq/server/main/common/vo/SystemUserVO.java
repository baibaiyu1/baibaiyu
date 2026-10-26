package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统设置-用户列表 VO（账号 + 所属部门/组织 + 已分配角色）
 * <p>
 * 「部门即组织」后不再有 {@code account_tenant.role} 旧枚举；
 * {@link #leader} 表示该用户是否为其所属部门（组织）的负责人。
 *
 */
@Data
public class SystemUserVO implements SerializableEnable {

    private Long id;

    private String nickname;

    private String username;

    private String email;

    private Boolean enabled;

    /**
     * 所属部门（组织）ID；为空表示尚未归属任何组织
     */
    private Long deptId;

    /**
     * 所属部门（组织）名称
     */
    private String deptName;

    /**
     * 是否为所属部门（组织）的负责人
     */
    private Boolean leader;

    /**
     * 已分配的角色ID
     */
    private List<Long> roleIds;

    /**
     * 已分配的角色名称
     */
    private List<String> roleNames;

    /**
     * 是否仍处于「首次登录待改密」状态（管理员新增/重置密码后为 true）
     */
    private Boolean mustChangePassword;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
