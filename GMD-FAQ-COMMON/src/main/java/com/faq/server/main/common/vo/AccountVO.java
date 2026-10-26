package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号VO
 *
 */
@Data
@EqualsAndHashCode
public class AccountVO implements SerializableEnable {
    private Long id;
    private String nickname;
    private String email;
    private String username;
    private Boolean enabled;
    /** 当前租户内的角色 */
    private String tenantRole;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private Boolean rememberLastTenant;
    /**
     * 是否必须先修改密码
     * <p>
     * 管理员新增用户、或管理员重置密码后为 true，前端据此弹窗强制改密。
     */
    private Boolean mustChangePassword;
    /** 所属部门ID */
    private Long deptId;
    /** 所属部门名称（联表填充） */
    private String deptName;
    /** 已分配的角色名称（联表填充） */
    private List<String> roleNames;
    /** 已分配的角色ID（联表填充） */
    private List<Long> roleIds;
}
