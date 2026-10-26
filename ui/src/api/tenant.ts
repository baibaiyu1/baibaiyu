import request from '@/utils/request'
import type {
  ApiResponse,
  PageResult,
} from '@/types'
import type {
  Tenant,
  TenantVO,
  TenantDTO,
  TenantCreateRequest,
  TenantSettingsRequest,
} from '@/types'

/**
 * 平台组织（内部租户）接口
 *
 * 「部门即组织」后，面向用户的「组织管理」界面已下线：
 * 组织就是部门树的节点，组织资料与成员都在「系统设置 - 组织与部门 / 用户列表」维护。
 * 这里只保留 `gmd_faq_tenant` 的内部维护能力（业务表 tenant_id 与租户拦截器的元数据来源）。
 * 原成员管理接口（/tenant/{id}/members）已随 account_tenant 一起删除，
 * 成员关系改由 `account.dept_id` 表达。
 */

/**
 * 分页查询
 * GET /tenant/list
 */
export function page(query: TenantDTO) {
  return request.get<ApiResponse<PageResult<TenantVO>>>('/api/tenant/list', {
    params: query
  })
}

/**
 * 列表查询
 * GET /tenant/list
 */
export function list() {
  return request.get<ApiResponse<Tenant[]>>('/api/tenant/list')
}

/**
 * 详情
 * GET /tenant/{id}
 */
export function detail(id: string) {
  return request.get<ApiResponse<Tenant>>(`/api/tenant/${id}`)
}

/**
 * 新增
 * POST /tenant
 */
export function save(data: TenantCreateRequest) {
  return request.post<ApiResponse<Tenant>>('/api/tenant', data)
}

/**
 * 修改
 * PUT /tenant/{id}
 */
export function update(id: string, data: Tenant) {
  return request.put<ApiResponse<boolean>>(`/api/tenant/${id}`, data)
}

/**
 * 删除
 * DELETE /tenant/{id}
 */
export function remove(id: string) {
  return request.delete<ApiResponse<boolean>>(`/api/tenant/${id}`)
}

/**
 * 更新平台组织基本设置
 * PUT /tenant/{id}/settings
 */
export function updateSettings(id: string, data: TenantSettingsRequest) {
  return request.put<ApiResponse<Tenant>>(`/api/tenant/${id}/settings`, data)
}
