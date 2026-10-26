import { getAgentRunURL, getRESTHeaders } from './request'

export interface VoiceConnection {
  serverUrl: string
  participantToken: string
  roomName: string
  participantIdentity: string
  expiresAt: number
}
export interface VoiceExchange { role: 'user' | 'assistant'; content: string }
export interface VoiceTurn {
  requestId: string
  text: string
  history?: VoiceExchange[]
  summary?: boolean
}
export interface VoiceReply {
  requestId: string
  speech: string
  action: 'NONE' | 'TASK' | 'INSTRUCTION'
  content: string
  runId: string | null
}

async function request<T>(path: string, session?: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${getAgentRunURL().replace(/\/run$/, '/voice')}${path}`, {
    method: body === undefined ? 'GET' : 'POST', headers: getRESTHeaders(session),
    body: body === undefined ? undefined : JSON.stringify(body), signal,
  })
  const result = await response.json()
  if (!response.ok || result.code !== 200) throw new Error(result.msg || '语音服务暂时不可用')
  return result.data
}
export const getVoiceAvailability = () => request<{ available: boolean }>('/config')
export const connectVoice = (session: string) => request<VoiceConnection>(`/${encodeURIComponent(session)}/connect`, session, {})
export const voiceTurn = (session: string, body: VoiceTurn, signal?: AbortSignal) =>
  request<VoiceReply>(`/${encodeURIComponent(session)}/turn`, session, body, signal)
