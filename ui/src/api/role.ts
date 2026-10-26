import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { DataScopeOption, SysRole, SysRoleSaveDTO, SysRoleVO } from '@/types/system'

/**
 * 查询角色列表（带菜单权限ID与已分配用户数）
 * GET /role/list
 *
 * @param params.deptId 传入时返回「平台级角色 + 该组织（部门）的专属角色」
 */
export function list(params?: { keyword?: string; enabled?: boolean; deptId?: string | number }) {
  return request.get<ApiResponse<SysRoleVO[]>>('/api/role/list', { params })
}

/**
 * 数据权限范围可选值
 * GET /role/data-scopes
 */
export function dataScopes() {
  return request.get<ApiResponse<DataScopeOption[]>>('/api/role/data-scopes')
}

/**
 * 角色详情
 * GET /role/{id}
 */
export function detail(id: string | number) {
  return request.get<ApiResponse<SysRoleVO>>(`/api/role/${id}`)
}

/**
 * 新增角色
 * POST /role
 */
export function save(entity: SysRoleSaveDTO) {
  return request.post<ApiResponse<SysRole>>('/api/role', entity)
}

/**
 * 修改角色
 * PUT /role/{id}
 */
export function update(id: string | number, entity: SysRoleSaveDTO) {
  return request.put<ApiResponse<boolean>>(`/api/role/${id}`, entity)
}

/**
 * 分配数据权限
 * PUT /role/{id}/data-scope
 */
export function updateDataScope(id: string | number, dataScope: string) {
  return request.put<ApiResponse<boolean>>(`/api/role/${id}/data-scope`, null, {
    params: { dataScope }
  })
}

/**
 * 查询角色已分配的用户ID列表
 * GET /role/{id}/users
 */
export function listAssignedUsers(id: string | number) {
  return request.get<ApiResponse<Array<string | number>>>(`/api/role/${id}/users`)
}

/**
 * 分配用户（全量覆盖）
 * PUT /role/{id}/users
 */
export function assignUsers(id: string | number, accountIds: Array<string | number>) {
  return request.put<ApiResponse<boolean>>(`/api/role/${id}/users`, accountIds)
}

/**
 * 查询某账号可分配的角色（平台级 + 其所属组织（部门）的角色）
 * GET /role/assignable
 */
export function listAssignable(accountId: string | number) {
  return request.get<ApiResponse<SysRoleVO[]>>('/api/role/assignable', {
    params: { accountId }
  })
}

/**
 * 删除角色
 * DELETE /role/{id}
 */
export function remove(id: string | number) {
  return request.delete<ApiResponse<boolean>>(`/api/role/${id}`)
}
