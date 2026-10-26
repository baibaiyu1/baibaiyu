/**
 * 资源发布审批类型定义
 *
 */

/** 审批流配置 */
export interface ApprovalFlow {
  id?: string | number | null
  /** 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP */
  resourceType: string
  /** 审批人账号ID，逗号分隔 */
  approverIds?: string | null
  /** 审批人名称快照，逗号分隔 */
  approverNames?: string | null
  remark?: string | null
  /** 是否启用审批（关闭时发布申请直接生效） */
  enabled?: boolean
}

/** 审批申请单 */
export interface ApprovalRequestVO {
  id: string | number
  resourceType: string
  resourceTypeDesc?: string
  resourceId: string
  resourceName?: string
  resourceVersion?: string
  applicantId?: number
  applicantName?: string
  approverId?: number
  approverName?: string
  /** PENDING / APPROVED / REJECTED / CANCELED */
  status: string
  statusDesc?: string
  opinion?: string
  remark?: string
  /** 当前用户是否为可处理人 */
  handleable?: boolean
  /** 是否为当前用户发起 */
  mine?: boolean
  createdAt?: string
  handledAt?: string
}

/** 提交发布申请的结果 */
export interface ApprovalSubmitResult {
  /** true 表示未启用审批、已直接发布 */
  autoApproved: boolean
  message?: string
  request?: ApprovalRequestVO | null
}

/** 审批流配置保存参数 */
export interface ApprovalFlowSaveDTO {
  resourceType: string
  enabled: boolean
  approverIds: Array<string | number>
  approverNames: string[]
  remark?: string
}

/** 审批申请单分页结果 */
export interface ApprovalPageResult {
  records: ApprovalRequestVO[]
  total: number
  size: number
  current: number
  pages: number
}

/** 资源类型选项 */
export interface ApprovalResourceTypeOption {
  value: string
  label: string
}
