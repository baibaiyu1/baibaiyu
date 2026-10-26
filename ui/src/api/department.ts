import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { Department, DepartmentVO } from '@/types/system'

/**
 * 查询部门树
 * GET /department/tree
 */
export function tree(params?: { name?: string; enabled?: boolean }) {
  return request.get<ApiResponse<DepartmentVO[]>>('/api/department/tree', { params })
}

/**
 * 查询部门平铺列表（下拉选择用）
 * GET /department/list
 */
export function list(params?: { name?: string; parentId?: string | number; enabled?: boolean }) {
  return request.get<ApiResponse<Department[]>>('/api/department/list', { params })
}

/**
 * 部门详情
 * GET /department/{id}
 */
export function detail(id: string | number) {
  return request.get<ApiResponse<DepartmentVO>>(`/api/department/${id}`)
}

/**
 * 指定部门及其全部下级部门ID（用户列表按部门树筛选）
 * GET /department/{id}/descendants
 */
export function descendants(id: string | number) {
  return request.get<ApiResponse<Array<string | number>>>(`/api/department/${id}/descendants`)
}

/**
 * 新增部门
 * POST /department
 */
export function save(entity: Department) {
  return request.post<ApiResponse<Department>>('/api/department', entity)
}

/**
 * 修改部门
 * PUT /department/{id}
 */
export function update(id: string | number, entity: Department) {
  return request.put<ApiResponse<boolean>>(`/api/department/${id}`, entity)
}

/**
 * 删除部门
 * DELETE /department/{id}
 */
export function remove(id: string | number) {
  return request.delete<ApiResponse<boolean>>(`/api/department/${id}`)
}
