package com.gmd.faq.server.main.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 租户创建请求
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCreateRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String code;
    private String description;
    private String contactName;
    private String contactEmail;
}
