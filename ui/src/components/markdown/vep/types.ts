/**
 * VEP (Vision Enhancement Protocol) 类型定义
 *
 * 定义 AI 视觉增强协议的完整 TypeScript 类型，
 * 包括 Card（信息卡片）和 Chart（图表）两种视觉组件。
 *
 */

/** VEP 顶层消息 */
export interface VEPMessage {
  role: 'assistant'
  content: string
  version: '1.0'
  vision: VisionItem
}

/** 视觉组件联合类型 */
export type VisionItem = CardItem | ChartItem

/** Card 信息卡片 */
export interface CardItem {
  id: string
  type: 'card'
  title?: string
  insight?: string
  data: CardField[]
}

/** Card 数据字段 */
export interface CardField {
  label: string
  value: string | number | boolean
  format?: CardFormat
  unit?: string
  status?: BadgeStatus
}

export type CardFormat = 'text' | 'number' | 'currency' | 'percent' | 'datetime' | 'badge'

export type BadgeStatus = 'success' | 'warning' | 'error' | 'info' | 'default'

/** Chart 图表 */
export interface ChartItem {
  id: string
  type: 'chart'
  title?: string
  insight?: string
  data: ChartData
}

export interface ChartData {
  chartType: ChartType
  xAxis?: string[]
  series: ChartSeries[]
  yAxisLabel?: string
}

export type ChartType = 'line' | 'bar' | 'pie' | 'area' | 'radar'

/** 图表数据系列 */
export interface ChartSeries {
  name: string
  data: number[] | PieDataItem[]
}

/** 饼图数据项 */
export interface PieDataItem {
  name: string
  value: number
}
