import { getAgentRunURL, getRESTHeaders } from './request'
interface UsageTotals {
  inputTokens: number | string | null; outputTokens: number | string | null; reasoningTokens: number | string | null
}
export interface UsageRun extends UsageTotals {
  runId: string; state: string | null; elapsedMs: number | string | null
  modelCalls: number | string; unknownCalls: number | string; failures: number | string
  retries: number | string; switches: number | string; startedAt?: string | null
}
export interface UsageRecord extends UsageTotals {
  runId: string; state: string | null; elapsedMs: number | string | null; startedAt?: string | null
  id: string; agentId: string; operation: string; modelName: string | null; errorStage: string | null; errorType: string | null
}
export async function getTelemetry(sessionId: string): Promise<{ runs: UsageRun[]; records: UsageRecord[] }> {
  const response=await fetch(`${getAgentRunURL().replace(/\/run$/, '/telemetry')}/${encodeURIComponent(sessionId)}`, { headers: getRESTHeaders(sessionId) })
  const result=await response.json()
  if (!response.ok || result.code!==200) throw new Error(result.msg || '运行用量暂时不可用')
  return result.data
}
