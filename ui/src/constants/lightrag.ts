/** LightRAG 的图谱检索数量与返回片段数分别配置。 */
export const LIGHTRAG_MODES = [
  { label: '图谱 + 向量（mix）', value: 'mix' },
  { label: '实体与关系（hybrid）', value: 'hybrid' },
  { label: '实体检索（local）', value: 'local' },
  { label: '关系检索（global）', value: 'global' },
  { label: '向量检索（naive）', value: 'naive' }
]

export const LIGHTRAG_DEFAULTS = {
  mode: 'mix',
  topK: 20,
  graphTopK: 60,
  maxEntityTokens: 6000,
  maxRelationTokens: 8000,
  maxTotalTokens: 30000,
  enableRerank: true
}
