/**
 * JSON 树拍平
 *
 * 把 JSON 拍平成「带层级的行列表」，而不是用递归子组件：
 * 折叠只需按 path 过滤可见行，比递归组件的状态传递更好控制，也便于做全部展开/收起。
 *
 * 独立成模块以便单测（递归、数组下标、空容器等边界）。
 */

/** 拍平后的一行 */
export interface JsonRow {
  /** 从根到本节点的路径，用作唯一键 */
  path: string
  /** 层级，用于缩进 */
  depth: number
  /** 键名或数组下标 */
  key: string
  /** 键名是否为数组下标 */
  isIndex: boolean
  /** 值类型 */
  type: string
  /** 容器节点折叠时的摘要，如 `{…} 3 项` */
  summary: string
  /** 标量值的展示文本（字符串带引号）；容器节点为空串 */
  value: string
  /** 是否可折叠 */
  expandable: boolean
}

/**
 * 判断值类型名
 */
export function typeName(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  return typeof value
}

/**
 * 取可折叠容器的子项数量
 */
export function childCount(value: unknown): number {
  if (Array.isArray(value)) return value.length
  if (value && typeof value === 'object') return Object.keys(value as object).length
  return 0
}

/**
 * 标量值的展示文本
 */
export function scalarText(value: unknown, type: string): string {
  if (type === 'string') return JSON.stringify(value)
  return String(value)
}

/**
 * 递归拍平成行列表
 *
 * @param value 当前节点
 * @param key 键名
 * @param path 路径
 * @param depth 层级
 * @param isIndex 是否为数组下标
 * @param out 输出累积
 */
export function flattenJson(
  value: unknown,
  key: string,
  path: string,
  depth: number,
  isIndex: boolean,
  out: JsonRow[]
): void {
  const type = typeName(value)
  const expandable = type === 'array' || type === 'object'

  out.push({
    path,
    depth,
    key,
    isIndex,
    type,
    summary: expandable ? `${type === 'array' ? '[…]' : '{…}'} ${childCount(value)} 项` : '',
    value: expandable ? '' : scalarText(value, type),
    expandable
  })

  if (!expandable) return

  if (Array.isArray(value)) {
    value.forEach((item, i) => flattenJson(item, String(i), `${path}/${i}`, depth + 1, true, out))
  } else {
    Object.entries(value as Record<string, unknown>).forEach(([k, v]) => {
      flattenJson(v, k, `${path}/${k}`, depth + 1, false, out)
    })
  }
}

/**
 * 把 JSON 文本解析并拍平
 *
 * @param text JSON 源文本
 * @returns ok=false 时带 message；ok=true 时 rows 为拍平结果
 */
export function buildJsonRows(text: string): {
  ok: boolean
  rows: JsonRow[]
  message?: string
} {
  const raw = (text ?? '').trim()
  if (!raw) return { ok: true, rows: [] }

  let data: unknown
  try {
    data = JSON.parse(raw)
  } catch (e) {
    return { ok: false, rows: [], message: e instanceof Error ? e.message : 'JSON 解析失败' }
  }

  const rows: JsonRow[] = []
  flattenJson(data, 'root', '$', 0, false, rows)
  return { ok: true, rows }
}

/**
 * 过滤出可见行（任一祖先被折叠则隐藏）
 *
 * @param rows 全部行
 * @param collapsed 已折叠的路径集合
 */
export function visibleJsonRows(rows: JsonRow[], collapsed: Set<string>): JsonRow[] {
  if (collapsed.size === 0) return rows
  return rows.filter((row) => {
    let idx = row.path.lastIndexOf('/')
    while (idx > 0) {
      if (collapsed.has(row.path.slice(0, idx))) return false
      idx = row.path.lastIndexOf('/', idx - 1)
    }
    return true
  })
}
