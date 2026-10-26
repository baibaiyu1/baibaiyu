package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 部门表（同时承载「组织」语义）
 * <p>
 * 自关联树结构：parent_id = 0 表示顶级部门。租户隔离（tenant_id）。
 * <p>
 * <b>部门即组织</b>：每个部门节点就是一个组织，账号的 {@code account.dept_id} 指向哪个部门，
 * 就属于哪个组织（组织成员 = 该部门及其全部下级部门的账号）。因此组织资料（编码/描述/联系人）
 * 与负责人账号都直接落在本表上，平台不再有独立的 tenant 组织界面。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.DEPARTMENT)
public class Department extends BaseTenantEntity {

    /**
     * 父部门ID（0=顶级）
     */
    private Long parentId;

    /**
     * 部门（组织）名称
     */
    private String name;

    /**
     * 组织编码
     */
    private String code;

    /**
     * 负责人姓名（文本，历史字段）
     */
    private String leader;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 显示顺序
     */
    private Integer sortOrder;

    /**
     * 组织描述
     */
    private String description;

    /**
     * 联系人姓名
     */
    private String contactName;

    /**
     * 联系人邮箱
     */
    private String contactEmail;

    /**
     * 备注
     */
    private String remark;

    /**
     * 子部门（仅树形查询时填充，不落库）
     */
    @TableField(exist = false)
    private List<Department> children;

    /**
     * 负责人账号ID列表（**不落库**）
     * <p>
     * 组织负责人支持多选，实际存放在 {@code gmd_faq_department_leader} 关联表
     * （V18 迁移；V17 的单列 {@code leader_account_id} 已被它取代）。
     * 这里保留字段是为了让「新增/修改部门」接口能直接从请求体接收该数组，
     * 由 {@code DepartmentService.updateLeaderAccounts} 写入关联表。
     */
    @TableField(exist = false)
    private List<Long> leaderAccountIds;
}
