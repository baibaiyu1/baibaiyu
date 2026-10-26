package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 资源发布审批流配置
 * <p>
 * 按「租户 + 资源类型」唯一，控制该类型资源的发布是否需要审批以及由谁审批。
 * {@code enabled} 语义为「是否启用审批」：关闭时提交发布申请将直接生效。
 *
 */
@Getter
@Setter
@TableName(value = TableConst.APPROVAL_FLOW)
public class ApprovalFlow extends BaseTenantEntity {

    /**
     * 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     */
    private String resourceType;

    /**
     * 审批人账号ID，逗号分隔
     */
    private String approverIds;

    /**
     * 审批人名称快照，逗号分隔
     */
    private String approverNames;

    /**
     * 备注
     */
    private String remark;
}
