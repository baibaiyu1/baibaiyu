import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types'
import type {
  ChatSessionCreateDTO,
  ChatMessageAppendDTO,
  ChatSessionQueryDTO,
  ChatSessionVO,
  ChatMessageVO,
  ChatArtifactVO,
  MessageBranchVO
} from '@/types'

const BASE = '/api/agent/chat/session'

export interface SessionAccessVO {
  canViewTeam: boolean
  canViewAll: boolean
}

/** Session management scopes available to the current account. */
export function access() {
  return request.get<ApiResponse<SessionAccessVO>>(`${BASE}/access`)
}

/**
 * 创建新会话
 * POST /agent/chat/session
 */
export function createSession(dto: ChatSessionCreateDTO) {
  return request.post<ApiResponse<ChatSessionVO>>(BASE, dto)
}

/**
 * 正常对话追加消息
 * POST /agent/chat/session/{sessionId}/message
 */
export function appendMessage(sessionId: string, dto: ChatMessageAppendDTO) {
  return request.post<ApiResponse<ChatMessageVO>>(`${BASE}/${sessionId}/message`, dto)
}

/**
 * 重新生成（新分支）
 * POST /agent/chat/session/{sessionId}/regenerate
 */
export function regenerateMessage(sessionId: string, dto: ChatMessageAppendDTO) {
  return request.post<ApiResponse<ChatMessageVO>>(`${BASE}/${sessionId}/regenerate`, dto)
}

/**
 * 切换历史分支
 * PUT /agent/chat/session/{sessionId}/current?messageId=xxx
 */
export function switchCurrentMessage(sessionId: string, messageId: string) {
  return request.put<ApiResponse<unknown>>(`${BASE}/${sessionId}/current`, null, {
    params: { messageId }
  })
}

/**
 * 当前对话路径上的消息分支信息
 * GET /agent/chat/session/{sessionId}/message-branches
 */
export function getMessageBranches(sessionId: string) {
  return request.get<ApiResponse<MessageBranchVO[]>>(`${BASE}/${sessionId}/message-branches`)
}

/**
 * 回显当前完整对话
 * GET /agent/chat/session/{sessionId}/messages/current
 */
export function getCurrentMessages(sessionId: string) {
  return request.get<ApiResponse<ChatMessageVO[]>>(`${BASE}/${sessionId}/messages/current`)
}

/**
 * 会话产物列表（智能体实际写出/修改的工作空间文件）
 * GET /agent/chat/session/{sessionId}/artifacts
 */
export function getArtifacts(sessionId: string) {
  return request.get<ApiResponse<ChatArtifactVO[]>>(`${BASE}/${sessionId}/artifacts`)
}

/**
 * 分页加载当前对话消息（向上滚动加载历史）
 * GET /agent/chat/session/{sessionId}/messages/paged?beforeDepth=xx&size=50
 */
export function getCurrentMessagesPaged(
  sessionId: string,
  params: { beforeDepth?: number | null; size?: number }
) {
  return request.get<ApiResponse<{
    messages: ChatMessageVO[]
    hasMore: boolean
    nextBeforeDepth: number | null
  }>>(`${BASE}/${sessionId}/messages/paged`, { params })
}

/**
 * 会话列表
 * GET /agent/chat/session/list
 */
export function listSessions(query?: ChatSessionQueryDTO) {
  return request.get<ApiResponse<ChatSessionVO[]>>(`${BASE}/list`, {
    params: query
  })
}

/**
 * 分页查询会话
 * GET /agent/chat/session/page
 */
export function pageSessions(query?: ChatSessionQueryDTO) {
  return request.get<ApiResponse<PageResult<ChatSessionVO>>>(`${BASE}/page`, {
    params: query
  })
}

/**
 * 会话详情
 * GET /agent/chat/session/{id}
 */
export function getSessionDetail(id: string) {
  return request.get<ApiResponse<ChatSessionVO>>(`${BASE}/${id}`)
}

/**
 * 置顶会话
 * PUT /agent/chat/session/{id}/pin
 */
export function pinSession(id: string) {
  return request.put<ApiResponse<unknown>>(`${BASE}/${id}/pin`)
}

/**
 * 取消置顶会话
 * PUT /agent/chat/session/{id}/unpin
 */
export function unpinSession(id: string) {
  return request.put<ApiResponse<unknown>>(`${BASE}/${id}/unpin`)
}

/**
 * 更新会话标题
 * PUT /agent/chat/session/{id}/title?title=xxx
 */
export function updateSessionTitle(id: string, title: string) {
  return request.put<ApiResponse<unknown>>(`${BASE}/${id}/title`, null, {
    params: { title }
  })
}

/**
 * 更新当前消息内容（后端通过 session 的 current_message_id 定位，调用方无需传 messageId）
 * PUT /agent/chat/session/{sessionId}/current-message/content
 */
export function updateCurrentMessageContent(sessionId: string, content: string) {
  return request.put<ApiResponse<unknown>>(`${BASE}/${sessionId}/current-message/content`, { content })
}

/**
 * 删除会话
 * DELETE /agent/chat/session/{id}
 */
export function deleteSession(id: string) {
  return request.delete<ApiResponse<unknown>>(`${BASE}/${id}`)
}

/**
 * 一键清空对话记录：删除指定智能体下当前登录用户的全部会话
 * DELETE /agent/chat/session/agent/{agentId}
 */
export function clearAgentSessions(agentId: string) {
  return request.delete<ApiResponse<{ deleted: number }>>(`${BASE}/agent/${agentId}`)
}

/**
 * 清空单条会话的对话记录（保留会话本身）
 * DELETE /agent/chat/session/{id}/messages
 */
export function clearSessionMessages(sessionId: string) {
  return request.delete<ApiResponse<{ deleted: number }>>(`${BASE}/${sessionId}/messages`)
}

/**
 * 删除单条游客会话（连带工作空间目录）
 * DELETE /agent/chat/session/guest/{id}
 *
 * 需要 agent:chat:guest:manage 权限点；传入非游客会话会被后端拒绝。
 */
export function deleteGuestSession(sessionId: string) {
  return request.delete<ApiResponse<{ deleted: number }>>(`${BASE}/guest/${sessionId}`)
}

/**
 * 批量清理游客会话（可选按智能体限定）
 * DELETE /agent/chat/session/guest?agentId=xx
 *
 * 不传 agentId 表示清理当前租户下全部游客会话。
 */
export function clearGuestSessions(agentId?: string) {
  return request.delete<ApiResponse<{ deleted: number }>>(`${BASE}/guest`, {
    params: { agentId }
  })
}
