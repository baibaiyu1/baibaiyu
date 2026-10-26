package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 资源发布审批申请单
 * <p>
 * 一次发布申请一条记录，状态流转：PENDING → APPROVED / REJECTED / CANCELED。
 * 历史申请单不可修改状态以外的内容，重新申请会生成新记录。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.APPROVAL_REQUEST)
public class ApprovalRequest extends BaseTenantEntity {

    /**
     * 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     */
    private String resourceType;

    /**
     * 资源ID
     */
    private String resourceId;

    /**
     * 资源名称快照
     */
    private String resourceName;

    /**
     * 资源版本快照
     */
    private String resourceVersion;

    /**
     * 申请人账号ID
     */
    private Long applicantId;

    /**
     * 申请人名称快照
     */
    private String applicantName;

    /**
     * 处理人账号ID
     */
    private Long approverId;

    /**
     * 处理人名称快照
     */
    private String approverName;

    /**
     * 状态：PENDING/APPROVED/REJECTED/CANCELED
     */
    private String status;

    /**
     * 审批意见
     */
    private String opinion;

    /**
     * 申请说明
     */
    private String remark;

    /**
     * 处理时间
     */
    private LocalDateTime handledAt;
}
