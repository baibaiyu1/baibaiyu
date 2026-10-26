package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.TenantRole;
import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号-租户关联表
 *
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(TableConst.ACCOUNT_TENANT)
public class AccountTenant implements SerializableEnable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long accountId;
    private Long tenantId;
    private TenantRole role;

    @TableField(fill = FieldFill.INSERT)
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
