package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门 VO（树形节点；每个节点同时是一个「组织」）
 *
 */
@Data
public class DepartmentVO implements SerializableEnable {

    private Long id;

    private Long parentId;

    private String name;

    /** 组织编码 */
    private String code;

    private String leader;

    /**
     * 负责人账号ID列表（可多选，关联 gmd_faq_account.id）
     * <p>
     * V18 起一个组织允许多位负责人（正/副职、多人共管）。
     */
    private List<Long> leaderAccountIds;

    /** 负责人账号的昵称/用户名，顺序与 {@link #leaderAccountIds} 一致，便于前端直接展示 */
    private List<String> leaderNames;

    private String phone;

    private String email;

    private Integer sortOrder;

    /** 组织描述 */
    private String description;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人邮箱 */
    private String contactEmail;

    private String remark;

    private Boolean enabled;

    private LocalDateTime createdAt;

    /**
     * 该部门直属账号数
     */
    private Long userCount;

    /**
     * 该部门及其全部下级部门的账号数（组织成员总数）
     */
    private Long memberCount;

    /**
     * 子部门
     */
    private List<DepartmentVO> children;
}
