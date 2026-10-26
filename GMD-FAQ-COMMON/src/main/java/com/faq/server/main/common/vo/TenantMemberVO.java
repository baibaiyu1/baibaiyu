package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 租户成员VO（含账户信息与租户角色）
 *
 */
@Data
@EqualsAndHashCode
public class TenantMemberVO implements SerializableEnable {
    private Long accountId;
    private String nickname;
    private String email;
    private String username;
    /** 租户角色 */
    private String tenantRole;
    /** 加入时间 */
    private LocalDateTime joinedAt;
    /** 是否已启用 */
    private Boolean enabled;
}
