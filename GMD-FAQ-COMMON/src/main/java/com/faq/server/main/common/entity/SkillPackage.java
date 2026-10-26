package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 技能包
 *
 */
@Getter
@Setter
@TableName(value = TableConst.SKILL)
public class SkillPackage extends BaseTenantEntity implements OwnedResource {

    /**
     * 技能包名称
     */
    private String name;

    /**
     * 技能别名
     */
    private String alias;

    /**
     * 技能描述
     */
    private String description;

    /**
     * 技能分类
     */
    private String category;

    /**
     * 个人资产归属账号；部门和平台资产为空，结合 ownershipType 判断归属。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long ownerId;

    /** 创建时所属部门；归属人调部门后保持不变。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long resourceDeptId;

    /**
     * 可见性：PRIVATE 按资产归属限制访问；TENANT 扩大到所属部门分支或平台。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String visibility;
    /** 资产归属；普通编辑不可变更，移交走资源治理接口。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownershipType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long owningDeptId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long maintainerId;
}
