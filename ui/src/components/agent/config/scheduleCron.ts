/**
 * 智能体编排页「定时任务」—— Quartz Cron 工具
 *
 * 与「自动化任务」的 CronBuilder 同源，但这里额外承载编排页特有的两点：
 * 1. 一次性任务用 **7 段表达式**（Quartz 支持可选的第 7 段「年」）表达，触发一次即 COMPLETE，
 *    因此「触发类型」可以直接由表达式段数推导，不需要额外存字段；
 * 2. 常用模板按「分钟级 / 小时级 / 每日固定时间 / 工作日 / 周末 / 指定周 / 月份」分组，
 *    与新增弹窗里的模板区一一对应。
 *
 */
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'

/** Cron 六段（秒 分 时 日 月 周） */
export interface CronParts {
  second: string
  minute: string
  hour: string
  day: string
  month: string
  week: string
}

/** 触发类型 */
export type ScheduleTriggerType = 'CRON' | 'ONCE'

/** 默认表达式：每小时整点 */
export const DEFAULT_CRON = '0 0 * * * ?'

/** 秒级选项 */
export const secondOptions = [
  { value: '0', label: '0秒' },
  ...Array.from({ length: 59 }, (_, i) => ({ value: String(i + 1), label: `${i + 1}秒` })),
  { value: '*/5', label: '每5秒' },
  { value: '*/10', label: '每10秒' },
  { value: '*/15', label: '每15秒' },
  { value: '*/30', label: '每30秒' }
]

/** 分级选项 */
export const minuteOptions = [
  { value: '0', label: '0分' },
  ...Array.from({ length: 59 }, (_, i) => ({ value: String(i + 1), label: `${i + 1}分` })),
  { value: '*', label: '每分' },
  { value: '*/5', label: '每5分' },
  { value: '*/10', label: '每10分' },
  { value: '*/15', label: '每15分' },
  { value: '*/30', label: '每30分' }
]

/** 时级选项 */
export const hourOptions = [
  { value: '*', label: '每小时' },
  ...Array.from({ length: 24 }, (_, i) => ({ value: String(i), label: `${i}点` }))
]

/** 日级选项 */
export const dayOptions = [
  { value: '*', label: '每天' },
  { value: '?', label: '不指定' },
  ...Array.from({ length: 31 }, (_, i) => ({ value: String(i + 1), label: `${i + 1}日` })),
  { value: 'L', label: '最后一天' }
]

/** 月级选项 */
export const monthOptions = [
  { value: '*', label: '每月' },
  ...Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: `${i + 1}月` }))
]

/** 周级选项 */
export const weekOptions = [
  { value: '?', label: '不指定' },
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' },
  { value: 'MON-FRI', label: '周一至周五' },
  { value: 'SAT,SUN', label: '周六、周日' }
]

/** 常用模板分组 */
export const cronPresetGroups: Array<{ label: string; presets: Array<{ label: string; cron: string }> }> = [
  {
    label: '分钟级',
    presets: [
      { label: '每5分钟', cron: '0 */5 * * * ?' },
      { label: '每10分钟', cron: '0 */10 * * * ?' },
      { label: '每30分钟', cron: '0 */30 * * * ?' }
    ]
  },
  {
    label: '小时级',
    presets: [
      { label: '每小时整点', cron: '0 0 * * * ?' },
      { label: '每2小时', cron: '0 0 */2 * * ?' }
    ]
  },
  {
    label: '每日固定时间',
    presets: [
      { label: '每天零点', cron: '0 0 0 * * ?' },
      { label: '每天早8点', cron: '0 0 8 * * ?' },
      { label: '每天中午12点', cron: '0 0 12 * * ?' },
      { label: '每天晚6点', cron: '0 0 18 * * ?' },
      { label: '每天晚10点', cron: '0 0 22 * * ?' }
    ]
  },
  {
    label: '工作日',
    presets: [
      { label: '工作日早8点', cron: '0 0 8 ? * MON-FRI' },
      { label: '工作日早晚9/18点', cron: '0 0 9,18 ? * MON-FRI' },
      { label: '工作日早9:30', cron: '0 30 9 ? * MON-FRI' }
    ]
  },
  {
    label: '周末',
    presets: [{ label: '周末早10点', cron: '0 0 10 ? * SAT,SUN' }]
  },
  {
    label: '指定周',
    presets: [
      { label: '周一早9点', cron: '0 0 9 ? * MON' },
      { label: '周五晚6点', cron: '0 0 18 ? * FRI' }
    ]
  },
  {
    label: '月份',
    presets: [
      { label: '每月1号零点', cron: '0 0 0 1 * ?' },
      { label: '每季度首日零点', cron: '0 0 0 1 1,4,7,10 ?' }
    ]
  }
]

/**
 * 表达式分段（一次性任务为 7 段，末段是「年」）
 *
 * @param cron Quartz 表达式
 */
export function splitCron(cron: string): string[] {
  return (cron || '').trim().split(/\s+/).filter(Boolean)
}

/**
 * 是否为一次性任务表达式（带「年」段）
 *
 * @param cron Quartz 表达式
 */
export function isOnceCron(cron: string): boolean {
  return splitCron(cron).length >= 7
}

/**
 * 解析表达式到六段选择器（一次性表达式只取前六段）
 *
 * @param cron Quartz 表达式
 */
export function parseCronToParts(cron: string): CronParts {
  const parts = splitCron(cron)
  return {
    second: parts[0] || '0',
    minute: parts[1] || '0',
    hour: parts[2] || '*',
    day: parts[3] || '*',
    month: parts[4] || '*',
    week: parts[5] || '?'
  }
}

/**
 * 六段选择器拼回表达式（Quartz 规则：周与日不能同时指定，指定周时「日」必须为 ?）
 *
 * @param parts 六段选择器
 */
export function buildCronFromParts(parts: CronParts): string {
  const day = parts.week && parts.week !== '?' ? '?' : parts.day
  return `${parts.second} ${parts.minute} ${parts.hour} ${day} ${parts.month} ${parts.week || '?'}`
}

/**
 * 由「执行时刻」生成一次性任务表达式（秒 分 时 日 月 ? 年）
 *
 * @param time 执行时刻
 */
export function buildOnceCron(time: Dayjs): string {
  return `${time.second()} ${time.minute()} ${time.hour()} ${time.date()} ${time.month() + 1} ? ${time.year()}`
}

/**
 * 由一次性任务表达式还原执行时刻（解析失败返回 null）
 *
 * @param cron Quartz 表达式
 */
export function parseOnceTime(cron: string): Dayjs | null {
  const parts = splitCron(cron)
  if (parts.length < 7) return null
  const [second, minute, hour, day, month, , year] = parts as [
    string, string, string, string, string, string, string
  ]
  const nums = [second, minute, hour, day, month, year].map(item => Number(item))
  if (nums.some(item => Number.isNaN(item))) return null
  const [s, mi, h, d, mo, y] = nums as [number, number, number, number, number, number]
  return dayjs(new Date(y, mo - 1, d, h, mi, s))
}

/** 一周中文名（Cron 的 1=周日 … 7=周六，这里只处理字母缩写） */
const weekNames: Record<string, string> = {
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  SUN: '周日'
}

/**
 * 人类可读的执行周期描述
 *
 * @param cron Quartz 表达式
 */
export function describeCron(cron: string): string {
  const parts = splitCron(cron)
  if (parts.length !== 6 && parts.length !== 7) return '格式不正确'
  const [second, minute, hour, day, month, week] = parts as [
    string, string, string, string, string, string
  ]

  if (parts.length === 7) {
    const time = parseOnceTime(cron)
    return time ? `${time.format('YYYY-MM-DD HH:mm:ss')} 执行一次` : '一次性执行'
  }

  // 常见表达式直接给口语化结论
  if (second === '0' && minute === '0' && hour === '*' && day === '*' && month === '*' && week === '?') {
    return '每小时执行'
  }
  if (second === '0' && minute === '0' && hour === '0' && day === '*' && month === '*' && week === '?') {
    return '每天零点执行'
  }
  if (minute.startsWith('*/') && hour === '*' && day === '*' && month === '*' && week === '?') {
    return `每${minute.replace('*/', '')}分钟执行`
  }
  if (hour.startsWith('*/') && day === '*' && month === '*' && week === '?') {
    return `每${hour.replace('*/', '')}小时执行`
  }

  const desc: string[] = []
  if (week === 'MON-FRI') desc.push('工作日')
  else if (week === 'SAT,SUN' || week === 'SUN,SAT') desc.push('周末')
  else if (week && week !== '?') {
    desc.push(week.split(',').map(item => weekNames[item] || item).join('、'))
  }
  if (month !== '*') desc.push(`${month}月`)
  if (day !== '*' && day !== '?') desc.push(`${day}日`)
  if (hour !== '*') desc.push(`${hour}点`)
  if (minute !== '0' && minute !== '*') {
    desc.push(minute.startsWith('*/') ? `每${minute.replace('*/', '')}分` : `${minute}分`)
  } else if (minute === '0' && hour !== '*') {
    desc.push('整点')
  } else if (minute === '*') {
    desc.push('每分钟')
  }
  if (second !== '0') {
    desc.push(second.startsWith('*/') ? `每${second.replace('*/', '')}秒` : `${second}秒`)
  }
  return desc.length > 0 ? `${desc.join('')}执行` : '自定义执行策略'
}

/**
 * 表达式是否合法（Quartz 的 6 段 / 7 段）
 *
 * @param cron Quartz 表达式
 */
export function isValidCron(cron: string): boolean {
  const parts = splitCron(cron)
  return parts.length === 6 || parts.length === 7
}
