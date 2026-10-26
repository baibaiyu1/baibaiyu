import type { KnowledgeCitation, ToolResultImage } from '@/types/chat'

/**
 * 尝试将字符串格式化为 JSON（美化），失败则返回原文
 */
export function formatToolDisplay(text: string): string {
  if (!text) return text
  const trimmed = text.trim()
  if (!trimmed) return text
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      const parsed = JSON.parse(trimmed)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return text
    }
  }
  return text
}

/**
 * 构建工具调用的 JSON 内容（用于保存到消息中）
 */
export function buildToolCallsContent(
  toolCalls: Array<{ id: string; name: string; args: string; result?: string; elapsed?: number;
    images?: ToolResultImage[]; artifacts?: import('@/types').ChatArtifactVO[]; citations?: KnowledgeCitation[]; hideDetails?: boolean }>
): string {
  if (toolCalls.length === 0) return ''

  const t = toolCalls[0]!
  const toolContent: Record<string, unknown> = {
    toolCallId: t.id,
    name: t.name,
    totalTimes: t.elapsed ?? 0,
    args: t.args ?? '',
    result: t.result ?? '',
    images: t.images ?? [],
    artifacts: t.artifacts ?? [],
    citations: t.citations ?? [],
    hideDetails: t.hideDetails ?? false,
  }

  return JSON.stringify(toolContent)
}

/**
 * 根据用户输入生成会话标题（截取前50字符）
 */
export function formatSessionTitle(input: string | null): string {
  let t = (input || '').trim()
  if (!t) return '新对话'

  t = t.replace(/<\/?(?:workspace-file|agent-tool|agent-skill)>/g, '')

  return t.length > 50 ? t.slice(0, 50) + '...' : t
}
