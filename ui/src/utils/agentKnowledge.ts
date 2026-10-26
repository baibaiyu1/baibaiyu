import { RAGMode } from '@/types/enums'

export interface AgentKnowledgeStrategy extends Record<string, unknown> {
  ragMode?: RAGMode
  topK?: number | null
  scoreThreshold?: number | null
  maxCallsPerRound?: number
  timeoutSeconds?: number
  concurrency?: number
  maxContextChars?: number
  maxChunkChars?: number
  includeImages?: boolean
  maxImages?: number
  historyTurns?: number
  strictEvidence?: boolean
  maxRetries?: number | null
  lightRag?: {
    mode?: string | null
    graphTopK?: number | null
    maxEntityTokens?: number | null
    maxRelationTokens?: number | null
    maxTotalTokens?: number | null
    enableRerank?: boolean | null
  }
}

export const KNOWLEDGE_STRATEGY_DEFAULTS = {
  maxCallsPerRound: 3, timeoutSeconds: 60, concurrency: 4, maxContextChars: 30000,
  maxChunkChars: 6000, includeImages: true, maxImages: 3, historyTurns: 0, strictEvidence: true,
}

/** 只在用户点击后应用，旧智能体保留自身策略。 */
export function recommendedKnowledgeStrategy(graph = false): AgentKnowledgeStrategy {
  return {
    ragMode: RAGMode.GENERIC, topK: 6, scoreThreshold: 0.5, maxCallsPerRound: 1,
    timeoutSeconds: graph ? 30 : 20, concurrency: 2, maxRetries: 0,
    maxContextChars: 18000, maxChunkChars: 4000, historyTurns: 1,
    includeImages: true, maxImages: 2, strictEvidence: true,
    lightRag: { mode: graph ? 'mix' : 'naive', graphTopK: 20,
      maxEntityTokens: 1500, maxRelationTokens: 2000, maxTotalTokens: 6000, enableRerank: false },
  }
}

export function knowledgeStrategyError(config: AgentKnowledgeStrategy | null): string | null {
  if (!config) return null
  if (config.ragMode != null && !['GENERIC', 'AGENTIC'].includes(config.ragMode)) return '请选择有效的知识库调用方式'
  const ranges: Array<[keyof AgentKnowledgeStrategy, string, number, number]> = [
    ['topK', '返回片段数（Top K）', 1, 1000], ['maxCallsPerRound', '每轮最多检索次数', 1, 8],
    ['timeoutSeconds', '单次检索总超时', 1, 180], ['concurrency', '同时检索的知识库数', 1, 4],
    ['maxContextChars', '总证据字符上限', 2000, 120000], ['maxChunkChars', '单个片段字符上限', 500, 20000],
    ['maxImages', '最多返回图片数', 1, 20], ['historyTurns', '携带历史问题轮数', 0, 4], ['maxRetries', '知识图谱最大重试次数', 0, 3],
  ]
  for (const [key, label, min, max] of ranges) {
    const value = config[key]
    if (value != null && (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max)) {
      return `${label}须为 ${min} 到 ${max} 之间的整数`
    }
  }
  if (config.scoreThreshold != null && (!Number.isFinite(config.scoreThreshold) || config.scoreThreshold < 0 || config.scoreThreshold > 1)) return '分数阈值须在 0 到 1 之间'
  if ((config.maxChunkChars ?? KNOWLEDGE_STRATEGY_DEFAULTS.maxChunkChars) > (config.maxContextChars ?? KNOWLEDGE_STRATEGY_DEFAULTS.maxContextChars)) return '单个片段字符上限不能超过总证据字符上限'
  const light = config.lightRag
  if (light?.mode != null && !['naive', 'mix', 'local', 'global', 'hybrid'].includes(light.mode)) return '请选择有效的知识图谱检索模式'
  for (const [key, label, max] of [['graphTopK', '图谱检索数量', 1000], ['maxEntityTokens', '实体 Token 上限', 1000000],
    ['maxRelationTokens', '关系 Token 上限', 1000000], ['maxTotalTokens', '总上下文 Token 上限', 1000000]] as const) {
    const value = light?.[key]
    if (value != null && (!Number.isInteger(value) || value < 1 || value > max)) return `知识图谱${label}须为 1 到 ${max} 之间的整数`
  }
  return null
}
