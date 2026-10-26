import type { ReasoningCapabilities, ReasoningSettings } from '@/types/reasoning'
import type { ModelConfigVO } from '@/types'
import type { ExtendConfigData } from '@/components/model/ExtendConfigEditor.vue'

const managedKeys = new Set(['reasoning', 'reasoning.effort', 'reasoning.summary', 'reasoning.mode',
  'reasoning_effort', 'thinking', 'thinking.type', 'thinking.budget_tokens', 'enable_thinking',
  'thinking_budget', 'thinkingBudget', 'thinkingConfig', 'think'])

export const defaultReasoning = (): ReasoningSettings => ({ mode: 'DEFAULT', showThinking: true })

export function modelReasoning(model?: ModelConfigVO): ReasoningSettings {
  return { ...defaultReasoning(), ...(model?.reasoningConfig || (model?.extendConfig as ExtendConfigData | null)?.reasoning) }
}

export function cleanReasoningExtensions(config: ExtendConfigData | null | undefined): ExtendConfigData {
  const { reasoning: _reasoning, reasoningProfile: _profile, ...rest } = config || {}
  return { ...rest, headers: { ...config?.headers },
    queryParams: Object.fromEntries(Object.entries(config?.queryParams || {}).filter(([key]) => !managedKeys.has(key))),
    bodyParams: Object.fromEntries(Object.entries(config?.bodyParams || {}).filter(([key]) => !managedKeys.has(key))) }
}

export function withoutReasoningOverride(override: Record<string, unknown> | null | undefined): Record<string, unknown> | null {
  const next = { ...override }
  delete next.reasoning
  if (next.extendConfig) next.extendConfig = cleanReasoningExtensions(next.extendConfig as ExtendConfigData)
  return Object.keys(next).length ? next : null
}

export function reasoningOnly(override: Record<string, unknown> | null | undefined) {
  return override?.reasoning ? { reasoning: override.reasoning } : null
}

export function hasSamplingOverride(override: Record<string, unknown> | null | undefined): boolean {
  return !!override && Object.keys(override).some(key => key !== 'reasoning')
}

export function describeReasoning(settings: ReasoningSettings, caps?: ReasoningCapabilities): string {
  const mode = { DEFAULT: '使用供应商默认', ENABLED: '开启', DISABLED: '关闭' }[settings.mode]
  const labels: Record<string, string> = { minimal: '最低', low: '低', medium: '中', high: '高', xhigh: '最高', max: '最高' }
  return [mode, settings.effort ? `${labels[settings.effort] || settings.effort}（${settings.effort}）` : '',
    settings.budgetTokens ? `${settings.budgetTokens} Token` : '', settings.showThinking ? '展示思考内容' : '隐藏思考内容',
    caps?.profile === 'NONE' ? '未识别推理能力' : ''].filter(Boolean).join(' · ')
}
