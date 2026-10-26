package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Data;

/**
 * 角色-账号关联表（用户分配）
 *
 */
@Data
@TableName(TableConst.ROLE_ACCOUNT)
public class RoleAccount implements SerializableEnable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 账号ID
     */
    private Long accountId;

    /**
     * 租户ID
     */
    private Long tenantId;
}
