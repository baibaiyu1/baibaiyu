/**
 * 高亮结果的按行切分
 *
 * highlight.js 的输出里，一个 <span> 可能横跨多行（块注释、模板字符串）。
 * 直接 split('\n') 会产出残缺标签 —— 行尾少了闭合、行首少了开启，
 * 浏览器会把后续内容全部吞进那个未闭合的 span，表现为「从某一行开始整片变色」。
 *
 * 这里维护一个未闭合标签栈：每行行首重开、行尾补闭合，
 * 使每一行都是独立合法的 HTML 片段，从而支持「逐行渲染 + 行号对齐 + 自动换行」。
 *
 * 独立成模块以便单测（这是最容易出现视觉错位、又最难靠肉眼定位的一段逻辑）。
 */

/**
 * 把高亮后的 HTML 按行切分，并为每行补齐跨行的未闭合 span
 *
 * @param html highlight.js 的输出
 * @returns 每行一个 HTML 片段
 */
export function splitHighlightedLines(html: string): string[] {
  const result: string[] = []
  let openTags: string[] = []

  for (const line of html.split('\n')) {
    const prefix = openTags.join('')
    const stack = [...openTags]
    const tagRe = /<(\/?)span([^>]*)>/g
    let match: RegExpExecArray | null

    while ((match = tagRe.exec(line)) !== null) {
      if (match[1] === '/') {
        // 防御：出现没有对应开标签的闭合标签时不弹空栈
        if (stack.length > 0) stack.pop()
      } else {
        stack.push(`<span${match[2]}>`)
      }
    }

    result.push(prefix + line + '</span>'.repeat(stack.length))
    openTags = stack
  }

  return result
}
