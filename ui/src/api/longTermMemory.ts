import request from '@/utils/request'
import type { ApiResponse } from '@/types'
import type { LongTermMemoryConfig } from '@/types'

/**
 * 列表查询
 * GET /long-term-memory/list
 */
export function list() {
  return request.get<ApiResponse<LongTermMemoryConfig[]>>('/api/long-term-memory/list')
}

/**
 * 详情
 * GET /long-term-memory/{id}
 */
export function detail(id: string) {
  return request.get<ApiResponse<LongTermMemoryConfig>>(`/api/long-term-memory/${id}`)
}

/**
 * 新增
 * POST /long-term-memory
 */
export function save(entity: LongTermMemoryConfig) {
  return request.post<ApiResponse<boolean>>('/api/long-term-memory', entity)
}

/**
 * 修改
 * PUT /long-term-memory
 */
export function update(entity: LongTermMemoryConfig) {
  return request.put<ApiResponse<boolean>>('/api/long-term-memory', entity)
}

/**
 * 删除
 * DELETE /long-term-memory
 */
export function remove(ids: string[]) {
  return request.delete<ApiResponse<boolean>>('/api/long-term-memory', { data: ids })
}

/**
 * 被哪些 Agent 使用
 * POST /long-term-memory/used-with-agent
 */
export function usedWithAgent(ids: string[]) {
  return request.post<ApiResponse<unknown[]>>('/api/long-term-memory/used-with-agent', ids)
}
