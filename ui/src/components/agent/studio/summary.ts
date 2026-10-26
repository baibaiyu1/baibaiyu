/**
 * 智能体编排工作台 —— 编排概览数据
 *
 * 各配置子表单（`AgentForm*`）在加载完自身的候选资源后，通过 `getSummary()`
 * 暴露「当前已选资源的名称」，供编排页把它们汇总成青云式的
 * 「已选 pill + 数量徽标」，无需在编排页重复请求同一批候选列表。
 *
 */

/** 单个资源分组的概览（名称 + 数量） */
export interface StudioSummaryGroup {
  /** 分组名称，如「工具」 */
  label: string
  /** 已选项名称（保持子表单中的选择顺序） */
  names: string[]
}

/** 编排概览：key 为分区标识（basic / model / tools / knowledge / advanced） */
export type StudioSummary = Record<string, StudioSummaryGroup>

/** 合并多个子表单返回的概览 */
export function mergeSummary(...parts: Array<StudioSummary | undefined | null>): StudioSummary {
  const merged: StudioSummary = {}
  for (const part of parts) {
    if (!part) continue
    for (const [key, group] of Object.entries(part)) {
      const current = merged[key]
      if (!current) {
        merged[key] = { label: group.label, names: [...group.names] }
        continue
      }
      for (const name of group.names) {
        if (!current.names.includes(name)) current.names.push(name)
      }
    }
  }
  return merged
}

/** 概览中的资源总数 */
export function summaryCount(summary: StudioSummary, key: string): number {
  return summary[key]?.names.length ?? 0
}

/** 多个概览分组的资源总数（用于卡片锚点的数量徽标） */
export function summaryTotal(summary: StudioSummary, keys: string[]): number {
  return keys.reduce((total, key) => total + summaryCount(summary, key), 0)
}
