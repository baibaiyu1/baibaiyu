package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

/**
 * 多租户基础实体类，继承 BaseEntity 并添加租户ID字段
 * <p>
 * <b>不要在这里加 owner_id / visibility</b>：这两列只存在于 5 张「资源型」表
 * （智能体 / 工具 / 技能 / 知识库 / MCP），加到基类会让**所有**继承本类的表
 * 在 SELECT 里带上不存在的列（历史事故：DepartmentMapper 直接报
 * {@code Unknown column 'owner_id' in 'field list'}）。
 * 资源型实体请各自实现 {@link OwnedResource} 并声明这两个字段。
 *
 */
@Getter
@Setter
public abstract class BaseTenantEntity extends BaseEntity {

    /**
     * 租户ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
}
