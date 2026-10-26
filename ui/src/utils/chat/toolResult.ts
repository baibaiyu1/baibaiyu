import type { DisplayMessage, ToolResultContent, ToolResultImage } from '@/types/chat'

/** 旧工具历史仍是同一 JSON 信封；没有 images 时保持原有展示。 */
export function parseToolResult(content: string): ToolResultContent | null {
  try {
    const parsed = JSON.parse(content) as ToolResultContent
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
    return parsed
  } catch {
    return null
  }
}

/** 只读取当前消息分支，图片脱离折叠的工具详情，作为本轮回答的组成部分。 */
export function toolResultImages(messages: DisplayMessage[]): ToolResultImage[] {
  const images: ToolResultImage[] = []
  const paths = new Set<string>()
  for (const message of messages) {
    if (message.role !== 'tool') continue
    const result = parseToolResult(message.content)
    if (!Array.isArray(result?.images)) continue
    for (const image of result.images) {
      if (!image || (!image.filePath && !image.error)) continue
      if (image.filePath && paths.has(image.filePath)) continue
      if (image.filePath) paths.add(image.filePath)
      images.push({ ...image, sourceTool: result.name })
    }
  }
  return images
}

export function isHiddenToolResult(message: DisplayMessage): boolean {
  return message.role === 'tool' && parseToolResult(message.content)?.hideDetails === true
}
