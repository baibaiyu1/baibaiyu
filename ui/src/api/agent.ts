import { resourceCreationHeaders } from '@/utils/resourceSpace'
import request from '@/utils/request'
import type {
  ApiResponse,
  PageResult,
  SkillPackage,
  ToolConfig,
} from '@/types'
import type { AgentDefinitionDTO, AgentDefinitionVO } from '@/types'

/**
 * 分页查询
 * GET /agent/definition/page
 */
export function page(query: AgentDefinitionDTO) {
  return request.get<ApiResponse<PageResult<AgentDefinitionVO>>>('/api/agent/definition/page', {
    params: query
  })
}

/**
 * 详情
 * GET /agent/definition/{id}
 */
export function detail(id: string) {
  return request.get<ApiResponse<AgentDefinitionVO>>(`/api/agent/definition/${id}`)
}

/**
 * 新增
 * POST /agent/definition
 */
export function save(vo: Partial<AgentDefinitionVO>) {
  return request.post<ApiResponse<AgentDefinitionVO>>('/api/agent/definition', vo, { headers: resourceCreationHeaders() })
}

/**
 * 修改
 * PUT /agent/definition
 */
export function update(vo: AgentDefinitionVO) {
  return request.put<ApiResponse<boolean>>('/api/agent/definition', vo)
}

/**
 * 删除
 * DELETE /agent/definition
 */
export function remove(ids: string[]) {
  return request.delete<ApiResponse<boolean>>('/api/agent/definition', { data: ids })
}

/**
 * 被哪些Agent使用
 * POST /agent/definition/used-with-agent
 */
export function usedWithAgent(ids: string[]) {
  return request.post<ApiResponse<unknown[]>>('/api/agent/definition/used-with-agent', ids)
}

/**
 * 获取所有Tag
 * GET /api/agent/definition/get/tags
 */
export function listTags() {
  return request.get<ApiResponse<string[]>>('/api/agent/definition/get/tags')
}

/** 新增租户内可复用的智能体标签。 */
export function createTag(name: string) {
  return request.post<ApiResponse<string>>('/api/agent/definition/tags', { name })
}

/** 删除租户内的标签目录项。被智能体使用时服务端会拒绝删除。 */
export function deleteTag(name: string) {
  return request.delete<ApiResponse<boolean>>('/api/agent/definition/tags', { data: { name } })
}

/**
 * 获取所有Tag
 * GET /api/agent/definition/get/tags
 */
export function allowFileType(id: string) {
  return request.get<ApiResponse<string[]>>(`/api/agent/definition/${id}/allow/file-type`)
}


/**
 * 执行工具
 */
export function agentDoTool(toolName: string, args: any) {
  return request.post<ApiResponse<any>>(`/api/runtime/tool/${toolName}/do`, args)
}

/**
 * 获取Agent启用的工具
 */
export function enabledToolsOfAgent(agentId: string) {
  return request.get<ApiResponse<ToolConfig[]>>(`/api/agent/definition/${agentId}/enabled/tools`)
}

/**
 * 获取Agent启用的技能包
 */
export function enabledSkillsOfAgent(agentId: string) {
  return request.get<ApiResponse<SkillPackage[]>>(`/api/agent/definition/${agentId}/enabled/skills`)
}

/**
 * 获取Agent启用的工作流
 */
export function enabledWorkflowsOfAgent(agentId: string) {
  return request.get<ApiResponse<unknown[]>>(`/api/agent/definition/${agentId}/enabled/workflows`)
}

/**
 * 保存「功能增强 · Web App 用户体验」配置（开场白 / 推荐问题）
 * PUT /agent/definition/{id}/ui-config
 */
export function saveUiConfig(
  agentId: string,
  body: { openingMessage?: string; openingQuestions?: string[] }
) {
  return request.put<ApiResponse<boolean>>(`/api/agent/definition/${agentId}/ui-config`, body)
}

/** Administrator-controlled guest access for a published agent. */
export function setExternalShare(agentId: string | number, enabled: boolean) {
  return request.put<ApiResponse<boolean>>(`/api/agent/definition/${agentId}/external-share`, { enabled })
}
