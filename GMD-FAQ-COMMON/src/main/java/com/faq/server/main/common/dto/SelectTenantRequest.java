package com.gmd.faq.server.main.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 租户选择请求
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectTenantRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long tenantId;
}
