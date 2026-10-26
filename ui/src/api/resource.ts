import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { AgentDefinitionVO } from '@/types'

/** 资源可见性与归属人（GET /resource/visibility 的返回项） */
export interface ResourceVisibilityVO {
  /** PRIVATE 按资产归属限制访问，TENANT 扩大到所属部门分支或平台。 */
  visibility?: string | null
  /** 个人资产归属账号；部门与平台资产为空，结合 ownershipType 判断归属。 */
  ownerId?: string | number | null
  canManage?: boolean
  canChangeVisibility?: boolean
  canUse?: boolean
  ownershipType?: 'PERSONAL' | 'DEPARTMENT' | 'PLATFORM'
  owningDeptId?: string | number | null
  maintainerId?: string | number | null
  spaceName?: string
  canTransfer?: boolean
}

export interface ResourceSpace {
  key: string
  name: string
  departmentId?: string | number | null
  canCreate: boolean
}

export function listSpaces() {
  return request.get<ApiResponse<ResourceSpace[]>>('/api/resource/spaces')
}

export function transferOwnership(resourceType: string, resourceId: string | number, data: {
  ownershipType: string; ownerId?: string; departmentId?: string; maintainerId?: string
}) {
  return request.put<ApiResponse<boolean>>('/api/resource/ownership', data, { params: { resourceType, resourceId } })
}

export interface ResourceGrantVO {
  accountId?: string | number
  departmentId?: string | number
  departmentName?: string
  allowUse: boolean
  nickname?: string
  username?: string
}

export interface ResourceGrantAccountVO {
  id: string | number
  nickname?: string
  username?: string
  deptName?: string
}

/** Explicit account grants for a resource. */
export function listGrants(resourceType: string, resourceId: string | number) {
  return request.get<ApiResponse<ResourceGrantVO[]>>('/api/resource/grants', {
    params: { resourceType, resourceId }
  })
}

export function saveGrant(resourceType: string, resourceId: string | number, accountId: string | number, allowUse: boolean) {
  return request.put<ApiResponse<boolean>>('/api/resource/grants', { accountId, allowUse }, {
    params: { resourceType, resourceId }
  })
}

export function saveGrants(resourceType: string, resourceId: string | number,
  accountIds: string[], departmentIds: string[], allowUse: boolean) {
  return request.put<ApiResponse<number>>('/api/resource/grants/batch',
    { accountIds, departmentIds, allowUse }, { params: { resourceType, resourceId } })
}

export function removeGrant(resourceType: string, resourceId: string | number,
  target: { accountId: string | number } | { departmentId: string | number }) {
  return request.delete<ApiResponse<boolean>>('/api/resource/grants', {
    params: { resourceType, resourceId, ...target }
  })
}

export function grantAccounts(resourceType: string, resourceId: string | number, keyword: string) {
  return request.get<ApiResponse<ResourceGrantAccountVO[]>>('/api/resource/grants/accounts', {
    params: { resourceType, resourceId, keyword }
  })
}

/**
 * 当前账号在指定资源类型下的收藏ID列表
 * GET /resource/favorite/ids
 */
export function favoriteIds(resourceType: string) {
  return request.get<ApiResponse<string[]>>('/api/resource/favorite/ids', {
    params: { resourceType }
  })
}

/**
 * 切换收藏状态
 * POST /resource/favorite/toggle
 * @returns true=已收藏，false=已取消收藏
 */
export function toggleFavorite(resourceType: string, resourceId: string | number) {
  return request.post<ApiResponse<boolean>>('/api/resource/favorite/toggle', null, {
    params: { resourceType, resourceId }
  })
}

/**
 * 修改资源可见性（支持 AGENT / TOOL / SKILL / KNOWLEDGE / MCP）
 * PUT /resource/visibility
 */
export function updateVisibility(
  resourceType: string,
  resourceId: string | number,
  visibility: 'PRIVATE' | 'TENANT'
) {
  return request.put<ApiResponse<boolean>>('/api/resource/visibility', null, {
    params: { resourceType, resourceId, visibility }
  })
}

/**
 * 批量读取资源可见性与归属人
 *
 * 卡片列表按 id 补读资源归属、可见范围和服务端判定的维护、移交、使用能力。
 * GET /resource/visibility
 */
export function visibilityMap(resourceType: string, resourceIds: Array<string | number>) {
  return request.get<ApiResponse<Record<string, ResourceVisibilityVO>>>('/api/resource/visibility', {
    params: { resourceType, resourceIds: resourceIds.join(',') }
  })
}

/**
 * Fork 智能体：复制一份归当前账号所有的副本
 * POST /resource/fork/agent/{id}
 */
export function forkAgent(id: string | number, name?: string) {
  return request.post<ApiResponse<AgentDefinitionVO>>(`/api/resource/fork/agent/${id}`, null, {
    params: name ? { name } : {}
  })
}
