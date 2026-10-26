/**
 * CSV / TSV 解析
 *
 * 独立成模块而非写在 SFC 里，是为了让解析逻辑可被直接单测 ——
 * 引号、转义、跨行字段这几类边界靠肉眼看界面是测不出来的。
 */

/**
 * 解析分隔符文本
 *
 * 单趟状态机。inQuotes 状态下，分隔符与换行都视为普通字符。
 * 处理三种真实写法：
 *   1. 引号包裹的字段（字段内可含分隔符）
 *   2. 字段内的 "" 转义为单个引号
 *   3. 引号字段内跨行（一个逻辑行占多个物理行）
 *
 * @param text 源文本
 * @param delim 分隔符，如 ',' 或 '\t'
 * @returns 二维字符串数组
 */
export function parseDelimited(text: string, delim: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  let i = 0

  while (i < text.length) {
    const ch = text[i]

    if (inQuotes) {
      if (ch === '"') {
        // "" 是一个转义的引号字符
        if (text[i + 1] === '"') {
          field += '"'
          i += 2
          continue
        }
        inQuotes = false
        i++
        continue
      }
      field += ch
      i++
      continue
    }

    if (ch === '"') {
      inQuotes = true
      i++
      continue
    }
    if (ch === delim) {
      row.push(field)
      field = ''
      i++
      continue
    }
    // 归一化 CRLF：CR 直接跳过
    if (ch === '\r') {
      i++
      continue
    }
    if (ch === '\n') {
      row.push(field)
      rows.push(row)
      row = []
      field = ''
      i++
      continue
    }

    field += ch
    i++
  }

  // 收尾：仅当最后一行确实有内容时才补，避免文件以换行结尾时多出一个空行
  if (field.length > 0 || row.length > 0) {
    row.push(field)
    rows.push(row)
  }

  return rows
}
