export interface ReasoningSettings {
  mode: 'DEFAULT' | 'ENABLED' | 'DISABLED'
  effort?: string | null
  budgetTokens?: number | null
  showThinking: boolean
}

export interface ReasoningCapabilities {
  profile: string
  canEnable: boolean
  canDisable: boolean
  efforts: string[]
  minBudget?: number | null
  maxBudget?: number | null
  summary: boolean
  hint: string
}
