/**
 * 资源可见性辅助
 *
 * 五类资源列表按 ID 批量补读服务端计算的可见性、资产归属、维护与移交资格，
 * 卡片菜单使用服务端能力字段；后端对实际操作再次鉴权。
 *
 */

import * as resourceApi from '@/api/resource'
import type { ResourceVisibilityVO } from '@/api/resource'
import { useAccountStore } from '@/stores'

/** PRIVATE 按资产归属限制访问，TENANT 扩大到所属部门分支或平台；显式授权独立生效。 */
export type ResourceVisibilityValue = 'PRIVATE' | 'TENANT'

/** 可补读可见性的列表项（各资源域 VO 均有 id） */
export interface VisibilityAware extends ResourceVisibilityVO {
  id?: string | number
  visibility?: string | null
  ownerId?: string | number | null
  canManage?: boolean
  canChangeVisibility?: boolean
  canUse?: boolean
}

/** 当前账号ID（未登录/读取失败时返回空串） */
function currentAccountId(): string {
  const accountStore = useAccountStore()
  const id = accountStore.userInfo?.id
  return id === undefined || id === null ? '' : String(id)
}

/**
 * 当前账号是否能管理该资源（前端展示口径）
 *
 * 优先使用服务端返回的 canManage，覆盖个人归属人、指定维护人、负责人和管理员。
 * 未补读时仅支持管理员与本人个人资产的保守回退；真正的拦截在后端。
 *
 * @param item 列表项（需已补读 ownerId）
 */
export function isResourceManageable(item: VisibilityAware): boolean {
  if (item.canManage !== undefined) return item.canManage
  const accountStore = useAccountStore()
  if (accountStore.isAdmin) return true
  const owner = item.ownerId
  if (owner === undefined || owner === null) return false
  const me = currentAccountId()
  return !!me && String(owner) === me
}

export function isResourceVisibilityChangeable(item: VisibilityAware): boolean {
  return item.canChangeVisibility === true
}

/**
 * 批量补读可见性与归属人并写回列表项
 *
 * 补读失败只告警，不影响列表展示（可见性属于附加信息）。
 *
 * @param resourceType 资源类型：AGENT / TOOL / SKILL / KNOWLEDGE / MCP
 * @param items        列表项（会被就地写入 visibility / ownerId）
 */
export async function mergeResourceVisibility(
  resourceType: string,
  items: VisibilityAware[]
): Promise<void> {
  const ids = items
    .map((item) => item.id)
    .filter((id): id is string | number => id !== undefined && id !== null)
  if (!ids.length) return
  try {
    const response = await resourceApi.visibilityMap(resourceType, ids)
    const map: Record<string, ResourceVisibilityVO> = response.data?.data || {}
    for (const item of items) {
      const value = map[String(item.id)]
      if (!value) continue
      if (value.visibility) item.visibility = value.visibility
      item.ownerId = value.ownerId ?? null
      item.canManage = value.canManage
      item.canChangeVisibility = value.canChangeVisibility
      item.canUse = value.canUse
      item.ownershipType = value.ownershipType
      item.owningDeptId = value.owningDeptId
      item.maintainerId = value.maintainerId
      item.spaceName = value.spaceName
      item.canTransfer = value.canTransfer
    }
  } catch (error) {
    console.warn('[可见性] 补读失败', error)
  }
}

/**
 * 修改资源可见性
 *
 * @param resourceType 资源类型
 * @param resourceId   资源ID
 * @param visibility   目标可见性
 */
export async function changeResourceVisibility(
  resourceType: string,
  resourceId: string | number,
  visibility: ResourceVisibilityValue
): Promise<void> {
  await resourceApi.updateVisibility(resourceType, resourceId, visibility)
}

/**
 * 计算切换后的可见性取值（卡片菜单用）
 *
 * @param current 当前可见性
 */
export function nextVisibility(current?: string | null): ResourceVisibilityValue {
  return current === 'PRIVATE' ? 'TENANT' : 'PRIVATE'
}
