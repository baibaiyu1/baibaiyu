package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 租户设置DTO
 * <p>
 * 自助注册与自助加入组织已下线，本 DTO 只承载组织基本资料的修改。
 *
 */
@Data
public class TenantSettingsDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 租户名称 */
    private String name;
    /** 租户描述 */
    private String description;
    /** 联系人 */
    private String contactName;
    /** 联系邮箱 */
    private String contactEmail;
}
