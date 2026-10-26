import { marked } from 'marked'
import type { KnowledgeCitationReference } from '@/types/chat'

const markerPattern = /\[\[cite:([\w-]{1,80})\]\]/g

/** 只读取正文标记，代码、链接地址、HTML 属性不能声明引用。 */
export function citedReferenceIds(content: string): string[] {
  if (!content.includes('[[cite:')) return []
  const ids: string[] = []
  marked.walkTokens(marked.lexer(content), token => {
    if (token.type === 'text' && !('tokens' in token && token.tokens?.length)) {
      for (const match of token.text.matchAll(markerPattern)) ids.push(match[1]!)
    }
  })
  return [...new Set(ids)]
}

export function citationLookup(citations: KnowledgeCitationReference[]): Map<string, KnowledgeCitationReference> {
  const lookup = new Map<string, KnowledgeCitationReference>()
  const ambiguous = new Set<string>()
  for (const citation of citations) {
    if (lookup.has(citation.referenceId)) ambiguous.add(citation.referenceId)
    lookup.set(citation.referenceId, citation)
  }
  for (const id of ambiguous) lookup.delete(id)
  return lookup
}

export type CitationTextPart = string | KnowledgeCitationReference

/** 交由 Vue 创建角标节点；标记和文档内容都不拼接为 HTML。 */
export function citationTextParts(text: string, citations: KnowledgeCitationReference[]): CitationTextPart[] {
  if (!text.includes('[[')) return [text]
  const lookup = citationLookup(citations)
  const parts: CitationTextPart[] = []
  let offset = 0
  for (const match of text.matchAll(markerPattern)) {
    if (match.index! > offset) parts.push(text.slice(offset, match.index))
    const citation = lookup.get(match[1]!)
    if (citation?.number) parts.push(citation)
    offset = match.index! + match[0].length
  }
  // 流式传输到半个标记时先等待，不把协议标记闪在正文里。
  parts.push(text.slice(offset).replace(/\[\[cite:[\w-]*\]?$|\[\[ci(?:t(?:e)?)?$/, ''))
  return parts
}

export function citationCopyText(content: string, citations: KnowledgeCitationReference[]): string {
  const lookup = citationLookup(citations)
  const literals: Array<[number, number]> = []
  marked.walkTokens(marked.lexer(content), token => {
    if (!['code', 'codespan', 'html', 'image', 'link'].includes(token.type) || !token.raw) return
    let start = content.indexOf(token.raw)
    while (start >= 0) {
      literals.push([start, start + token.raw.length])
      start = content.indexOf(token.raw, start + token.raw.length)
    }
  })
  const text = content.replace(markerPattern, (marker: string, id: string, offset: number) => {
    if (literals.some(([start, end]) => offset >= start && offset < end)) return marker
    const citation = lookup.get(id)
    return citation ? `[${citation.number}]` : ''
  })
  const used = new Set(citedReferenceIds(content))
  const sources = citations.filter(citation => used.has(citation.referenceId) && lookup.get(citation.referenceId) === citation)
    .sort((a, b) => a.number - b.number)
  return sources.length ? `${text}\n\n参考文档\n${sources.map(citation => `[${citation.number}] ${citation.fileName}（${citation.knowledgeName}）`).join('\n')}` : text
}
