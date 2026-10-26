/**
 * VEP 协议解析工具
 *
 * 提供 vep 代码块提取、JSON 解析等纯函数工具。
 * 镜像 uip.ts 的设计模式。
 *
 */

import type { VEPMessage } from '@/components/markdown/vep/types'

/** vep 代码块的 HTML 正则（code-handler 输出 <pre class="vep">） */
const VEP_HTML_REGEX = /<pre\s+class="vep">([\s\S]*?)<\/pre>/gi

/** 原始 markdown 中的 ```vep 匹配（兜底） */
const VEP_MD_REGEX = /```vep\s*\n([\s\S]*?)\s*```/g

/** HTML 实体解码 */
function decodeHtmlEntities(str: string): string {
  return str
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}

/** 剥离 HTML 标签 */
function stripHtmlTags(str: string): string {
  return str.replace(/<[^>]*>/g, '')
}

/**
 * 从渲染后 HTML 中提取 vep 代码块
 *
 * code-handler 对 vep 语言输出 <pre class="vep">，
 * 提取时清理 HTML 实体和残留标签，还原原始 JSON。
 */
export function extractVEPBlocks(
  html: string
): Array<{ placeholder: string; code: string; fullMatch: string }> {
  const blocks: Array<{ placeholder: string; code: string; fullMatch: string }> = []
  const regex = new RegExp(VEP_HTML_REGEX.source, VEP_HTML_REGEX.flags)
  let match: RegExpExecArray | null
  let idx = 0

  while ((match = regex.exec(html)) !== null) {
    const preBlock = match[0]
    const code = stripHtmlTags(decodeHtmlEntities((match[1] || '').trim()))
    blocks.push({ placeholder: `__VEP_PLACEHOLDER_${idx}__`, code, fullMatch: preBlock })
    idx++
  }
  return blocks
}

/**
 * 安全解析 VEP JSON，校验必要字段存在性
 */
export function parseVEPJson(code: string): VEPMessage | null {
  try {
    const parsed = JSON.parse(code)
    if (
      parsed &&
      typeof parsed === 'object' &&
      parsed.vision &&
      !Array.isArray(parsed.vision)
    ) {
      return parsed as VEPMessage
    }
    return null
  } catch {
    return null
  }
}

/**
 * 判断文本是否包含 vep 代码块
 */
export function hasVEPBlock(text: string): boolean {
  VEP_MD_REGEX.lastIndex = 0
  return VEP_MD_REGEX.test(text)
}

/**
 * 从文本中剔除 vep 代码块
 */
export function stripVEPBlock(text: string): string {
  VEP_MD_REGEX.lastIndex = 0
  return text.replace(VEP_MD_REGEX, '').trim()
}
