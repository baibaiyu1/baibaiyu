import { getAgentRunURL, getRESTHeaders } from './request'

export interface AgentInstruction {
  id: string
  runId: string
  requestId: string
  content: string
  state: 'RECEIVED' | 'INJECTING' | 'APPLIED' | 'CANCELLED' | 'EXPIRED' | 'UNCONFIRMED'
}
export interface InstructionInbox {
  enabled: boolean
  runId: string | null
  accepting: boolean
  messages: AgentInstruction[]
}

async function request<T>(sessionId: string, body?: unknown): Promise<T> {
  const url = `${getAgentRunURL().replace(/\/run$/, '/instructions')}/${encodeURIComponent(sessionId)}`
  const response = await fetch(url, {
    method: body ? 'POST' : 'GET', headers: getRESTHeaders(sessionId),
    body: body ? JSON.stringify(body) : undefined,
  })
  const result = await response.json()
  if (!response.ok || result.code !== 200) throw new Error(result.msg || '补充要求状态暂时不可用')
  return result.data
}
export const getInstructions = (sessionId: string) => request<InstructionInbox>(sessionId)
export const submitInstruction = (sessionId: string, runId: string, requestId: string, content: string) =>
  request<AgentInstruction>(sessionId, { runId, requestId, content })
