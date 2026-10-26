import { getAgentRunURL, getRESTHeaders } from './request'

export interface QueueOptions {
  memoryActive: boolean
  planActive: boolean
  toolProcessActive: boolean
  fileIds: string[]
  planMode: boolean
}
export interface QueuedMessage {
  id: string
  requestId: string
  content: string
  options: QueueOptions
  state: 'QUEUED' | 'RUNNING' | 'WAITING' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'INTERRUPTED' | 'EXPIRED' | 'STEERED'
  runId: string | null
}
export interface QueueSnapshot {
  state: 'IDLE' | 'RUNNING' | 'STOPPING' | 'WAITING' | 'INTERRUPTED'
  paused: boolean
  runId: string | null
  nodeId: string | null
  messages: QueuedMessage[]
}
async function request<T>(session: string, method = 'GET', suffix = '', body?: unknown): Promise<T> {
  const response = await fetch(`${getAgentRunURL().replace(/\/run$/, '/queue')}/${encodeURIComponent(session)}${suffix}`, {
    method, headers: getRESTHeaders(session), body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = await response.json()
  if (!response.ok || result.code !== 200) throw new Error(result.msg || '消息队列暂时不可用')
  return result.data
}
export const getQueue = (session: string) => request<QueueSnapshot>(session)
export const enqueueMessage = (session: string, requestId: string, content: string, options: QueueOptions) =>
  request<QueuedMessage>(session, 'POST', '', { requestId, content, options })
export const editQueuedMessage = (session: string, id: string, content: string) => request(session, 'PUT', `/${id}`, { content })
export const cancelQueuedMessage = (session: string, id: string) => request(session, 'DELETE', `/${id}`)
export const steerQueuedMessage = (session: string, id: string, runId: string) =>
  request<QueuedMessage>(session, 'POST', `/${id}/steer`, { runId })
export const continueQueue = (session: string) => request(session, 'POST', '/continue')
