import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { MenuTypeOption, SysMenu, SysMenuSaveDTO, SysMenuVO } from '@/types/system'

/**
 * 查询菜单树
 * GET /menu/tree
 */
export function tree(params?: { keyword?: string; menuType?: string }) {
  return request.get<ApiResponse<SysMenuVO[]>>('/api/menu/tree', { params })
}

/**
 * 查询菜单平铺列表
 * GET /menu/list
 */
export function list() {
  return request.get<ApiResponse<SysMenu[]>>('/api/menu/list')
}

/**
 * 菜单类型可选值
 * GET /menu/menu-types
 */
export function menuTypes() {
  return request.get<ApiResponse<MenuTypeOption[]>>('/api/menu/menu-types')
}

/**
 * 菜单详情（含按钮权限列表）
 * GET /menu/{id}
 */
export function detail(id: string | number) {
  return request.get<ApiResponse<SysMenuVO>>(`/api/menu/${id}`)
}

/**
 * 新增菜单
 * POST /menu
 */
export function save(entity: SysMenuSaveDTO) {
  return request.post<ApiResponse<SysMenu>>('/api/menu', entity)
}

/**
 * 修改菜单
 * PUT /menu/{id}
 */
export function update(id: string | number, entity: SysMenuSaveDTO) {
  return request.put<ApiResponse<boolean>>(`/api/menu/${id}`, entity)
}

/**
 * 删除菜单
 * DELETE /menu/{id}
 */
export function remove(id: string | number) {
  return request.delete<ApiResponse<boolean>>(`/api/menu/${id}`)
}
