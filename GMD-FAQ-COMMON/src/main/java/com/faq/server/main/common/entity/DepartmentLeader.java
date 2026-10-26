package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（组织）负责人关联表
 * <p>
 * 「部门即组织」后一个组织可以有多位负责人（正/副职、多人共管），
 * 因此负责人在 V18 迁移里从 {@code gmd_faq_department.leader_account_id} 单列
 * 改为本关联表的多对多关系。{@code (department_id, account_id)} 唯一。
 *
 */
@Data
@TableName(TableConst.DEPARTMENT_LEADER)
public class DepartmentLeader implements SerializableEnable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 部门（组织）ID
     */
    private Long departmentId;

    /**
     * 负责人账号ID
     */
    private Long accountId;

    /**
     * 租户ID
     */
    private Long tenantId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
}
