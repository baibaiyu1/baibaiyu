import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type {
  ApprovalFlow,
  ApprovalFlowSaveDTO,
  ApprovalPageResult,
  ApprovalRequestVO,
  ApprovalResourceTypeOption,
  ApprovalSubmitResult
} from '@/types/approval'

/**
 * 可审批的资源类型（下拉与页签渲染）
 * GET /approval/resource-types
 */
export function resourceTypes() {
  return request.get<ApiResponse<ApprovalResourceTypeOption[]>>('/api/approval/resource-types')
}

/**
 * 审批流配置列表（未配置的类型返回默认关闭项）
 * GET /approval/flow/list
 */
export function listFlows() {
  return request.get<ApiResponse<ApprovalFlow[]>>('/api/approval/flow/list')
}

/**
 * 保存审批流配置
 * PUT /approval/flow
 */
export function saveFlow(data: ApprovalFlowSaveDTO) {
  return request.put<ApiResponse<boolean>>('/api/approval/flow', data)
}

/**
 * 提交发布申请（未启用审批时直接生效）
 * POST /approval/request
 */
export function submit(data: {
  resourceType: string
  resourceId: string | number
  resourceName?: string
  resourceVersion?: string
  remark?: string
}) {
  return request.post<ApiResponse<ApprovalSubmitResult>>('/api/approval/request', data)
}

/**
 * 审批申请单分页查询
 * GET /approval/request/page
 */
export function page(params: {
  page?: number
  size?: number
  scope?: string
  status?: string
  resourceType?: string
  keyword?: string
}) {
  return request.get<ApiResponse<ApprovalPageResult>>('/api/approval/request/page', { params })
}

/**
 * 待我审批数量
 * GET /approval/request/pending-count
 */
export function pendingCount() {
  return request.get<ApiResponse<number>>('/api/approval/request/pending-count')
}

/**
 * 通过审批（通过后资源随之发布生效）
 * POST /approval/request/{id}/approve
 */
export function approve(id: string | number, opinion?: string) {
  return request.post<ApiResponse<boolean>>(`/api/approval/request/${id}/approve`, { opinion })
}

/**
 * 拒绝审批
 * POST /approval/request/{id}/reject
 */
export function reject(id: string | number, opinion?: string) {
  return request.post<ApiResponse<boolean>>(`/api/approval/request/${id}/reject`, { opinion })
}

/**
 * 撤回申请（申请人本人）
 * POST /approval/request/{id}/cancel
 */
export function cancel(id: string | number) {
  return request.post<ApiResponse<boolean>>(`/api/approval/request/${id}/cancel`)
}

export type { ApprovalRequestVO }
