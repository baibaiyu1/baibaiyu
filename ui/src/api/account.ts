import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { AccountVO } from '@/types'
import type { AccountCreateRequest, AccountCredentialVO } from '@/types'

/**
 * 账号详情
 * GET /account/{id}
 *
 * 说明：原「全部账户」页已下线（与「用户列表」重复），因此这里不再提供列表查询；
 * 当前登录用户的信息由 `accountStore.fetchCurrentUserInfo` 走本接口获取。
 */
export function detail(id: string) {
  return request.get<ApiResponse<AccountVO>>(`/api/account/${id}`)
}

/**
 * 管理员新增用户（系统已关闭自助注册，账号只能由拥有 system:user:add 的管理员创建）
 * POST /account
 *
 * 组织归属由 `deptId` 决定（留空即无组织账号）；密码留空时由服务端随机生成，
 * 明文仅在本次响应中返回一次。
 */
export function create(entity: AccountCreateRequest) {
  return request.post<ApiResponse<AccountCredentialVO>>('/api/account', entity)
}

/**
 * 删除用户
 * DELETE /account
 */
export function remove(ids: string[]) {
  return request.delete<ApiResponse<boolean>>('/api/account', { data: ids })
}

/**
 * 禁用/激活用户
 * PUT /account/{id}/toggle-enabled
 */
export function toggleEnabled(id: string, enabled: boolean) {
  return request.put<ApiResponse<boolean>>(`/api/account/${id}/toggle-enabled`, null, {
    params: { enabled }
  })
}

/**
 * 管理员重置用户密码
 * PUT /account/{id}/reset-password
 *
 * 新密码留空时由服务端随机生成，明文仅在本次响应中返回一次。
 */
export function resetPassword(id: string, newPassword?: string) {
  return request.put<ApiResponse<AccountCredentialVO>>(`/api/account/${id}/reset-password`, null, {
    params: newPassword ? { newPassword } : {}
  })
}
