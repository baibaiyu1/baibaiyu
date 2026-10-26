package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Data;

/**
 * 角色-菜单权限关联表
 *
 */
@Data
@TableName(TableConst.ROLE_MENU)
public class RoleMenu implements SerializableEnable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 角色ID
     */
    private Long roleId;

    /**
     * 菜单ID
     */
    private Long menuId;

    /**
     * 租户ID
     */
    private Long tenantId;
}
