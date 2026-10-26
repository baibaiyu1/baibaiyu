import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { SystemUserQuery, SystemUserVO } from '@/types/system'

/**
 * 查询系统设置-用户列表（账号 + 所属部门（组织） + 已分配角色）
 * GET /system/user/list
 *
 * 可见范围由后端按调用者的数据权限范围收敛：
 * 全部 / 本部门 / 本部门及以下 / 仅本人；未归属组织的账号只有「全部」档能看见。
 */
export function list(query?: SystemUserQuery) {
  return request.get<ApiResponse<SystemUserVO[]>>('/api/system/user/list', { params: query })
}

/**
 * 设置用户所属部门（组织）
 * PUT /system/user/{id}/department
 *
 * `deptId` 传 null 表示「移出组织」——账号保留，只是不再归属任何部门。
 */
export function updateDepartment(id: string | number, deptId: string | number | null) {
  return request.put<ApiResponse<boolean>>(`/api/system/user/${id}/department`, null, {
    params: deptId === null || deptId === undefined ? {} : { deptId }
  })
}

/**
 * 把用户移出所属组织（保留账号）
 * PUT /system/user/{id}/department
 */
export function removeFromDepartment(id: string | number) {
  return updateDepartment(id, null)
}

/**
 * 分配角色（全量覆盖）
 * PUT /system/user/{id}/roles
 *
 * 部门级角色只能分配给该组织内的账号，跨组织分配会被服务端拒绝。
 */
export function assignRoles(id: string | number, roleIds: Array<string | number>) {
  return request.put<ApiResponse<boolean>>(`/api/system/user/${id}/roles`, roleIds)
}
