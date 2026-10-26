import { getAgentRunURL, getRESTHeaders } from './request'

export interface PlanDocument {
  status: 'NONE' | 'PLANNING' | 'AWAITING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'STALE'
  revision: number
  digest: string | null
  content: string
  path: string
  approvalCallId: string | null
}
export async function getPlanDocument(session: string): Promise<PlanDocument> {
  const response = await fetch(`${getAgentRunURL().replace(/\/run$/, '/plan')}/${encodeURIComponent(session)}`, {
    headers: getRESTHeaders(session),
  })
  const result = await response.json()
  if (!response.ok || result.code !== 200) throw new Error(result.msg || '方案暂时不可用')
  return result.data
}
