package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.enums.TenantRole;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 租户成员添加请求
 *
 */
@Data
public class TenantMemberAddDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户账号（用户名） */
    private String username;
    private TenantRole role;
}
