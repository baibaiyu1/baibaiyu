import { resourceCreationHeaders } from '@/utils/resourceSpace'
import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types'
import type { KnowledgeBaseConfigDTO, KnowledgeBaseConfigVO, KnowledgeSourceVO } from '@/types'
import type { KnowledgeBaseConfig } from '@/types'

export interface KnowledgeModelOption {
  id: string
  name: string
  modelId: string
  providerName: string
  usages: string[]
  dimension?: number
}

export function modelOptions() {
  return request.get<ApiResponse<KnowledgeModelOption[]>>('/api/knowledge/config/model-options')
}

export function syncLightRagModels(id: string) {
  return request.put<ApiResponse<{ status: string; restart_required: boolean; rebuild_required: boolean } | null>>(
    `/api/knowledge/config/${id}/lightrag/models`)
}

/**
 * 知识库来源字典（后端驱动，新增来源无需改前端）
 * GET /knowledge/config/sources
 */
export function listSources() {
  return request.get<ApiResponse<KnowledgeSourceVO[]>>('/api/knowledge/config/sources')
}

/**
 * 分页查询
 * GET /knowledge/config/page
 */
export function page(query: KnowledgeBaseConfigDTO) {
  return request.get<ApiResponse<PageResult<KnowledgeBaseConfigVO>>>(
    '/api/knowledge/config/page',
    { params: query }
  )
}

/**
 * 详情
 * GET /knowledge/config/{id}
 */
export function detail(id: string) {
  return request.get<ApiResponse<KnowledgeBaseConfigVO>>(`/api/knowledge/config/${id}`)
}

/** 验证管理权限和 LightRAG 后端服务连接。 */
export function prepareLightRagManagement(id: string) {
  return request.get<ApiResponse<boolean>>(`/api/knowledge/config/${id}/lightrag/management`)
}

/**
 * 新增
 * POST /knowledge/config
 */
export function save(entity: KnowledgeBaseConfig) {
  return request.post<ApiResponse<boolean>>('/api/knowledge/config', entity, { headers: resourceCreationHeaders() })
}

/**
 * 修改
 * PUT /knowledge/config
 */
export function update(entity: KnowledgeBaseConfig) {
  return request.put<ApiResponse<boolean>>('/api/knowledge/config', entity)
}

/**
 * 删除
 * DELETE /knowledge/config
 */
export function remove(ids: string[]) {
  return request.delete<ApiResponse<boolean>>('/api/knowledge/config', { data: ids })
}

/**
 * 被哪些Agent使用
 * POST /knowledge/config/used-with-agent
 */
export function usedWithAgent(ids: string[]) {
  return request.post<ApiResponse<unknown[]>>('/api/knowledge/config/used-with-agent', ids)
}
