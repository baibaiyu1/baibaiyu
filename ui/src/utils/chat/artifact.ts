import type { DisplayMessage } from '@/types'
import type { ChatArtifactVO } from '@/types/vo'
import { parseToolResult } from './toolResult'

/** 新记录沿工具调用定位；旧记录按本轮的来源工具和时间定位。 */
export function attachArtifacts(messages: DisplayMessage[], artifacts: ChatArtifactVO[]): DisplayMessage[] {
  if (!artifacts.length || !messages.length) return messages
  const turns: Array<{ start: number; end: number; time: number }> = []
  messages.forEach((message, index) => {
    if (!turns.length || message.role === 'user') {
      turns.push({ start: index, end: index, time: message.role === 'user'
        ? new Date(message.createdAt ?? '').getTime() : Number.NaN })
    } else {
      turns[turns.length - 1]!.end = index
    }
  })
  const result = messages.map(message => ({ ...message }))
  for (const artifact of artifacts) {
    // 新消息自身携带产物快照；接口的最新文件列表不能把它移到别的轮次。
    if (messages.some(message => message.role === 'tool'
      && parseToolResult(message.content)?.artifacts?.some(item => item.filePath === artifact.filePath))) continue
    const time = new Date(artifact.createTime).getTime()
    let target = -1
    for (let index = 0; index < turns.length; index++) {
      if (Number.isFinite(turns[index]!.time) && turns[index]!.time <= time) target = index
    }
    // 分页可能从一轮回答中间开始；开头缺少提问时，早于下一次提问的文件属于这个残余轮次。
    if (target < 0 && Number.isFinite(time) && result[turns[0]!.start]?.role !== 'user') target = 0
    // 完整轮次中比最早可见提问还早的文件不应误挂到最新一轮。
    if (target < 0 && Number.isFinite(time) && turns.some(turn => Number.isFinite(turn.time))) continue
    if (target < 0) target = turns.length - 1
    const turn = turns[target]!
    const candidates = result.slice(turn.start, turn.end + 1)
    const tools = candidates.filter(message => message.role === 'tool' && (!artifact.sourceTool
      || parseToolResult(message.content)?.name === artifact.sourceTool))
    // 产物记录先于工具消息入库，同一来源的第一个后续结果最接近实际完成边界。
    const following = tools.find(message => new Date(message.createdAt ?? '').getTime() >= time)
    const preceding = tools.filter(message => new Date(message.createdAt ?? '').getTime() <= time)
    const earlier = candidates.filter(message => message.role !== 'system' && !message.isMemoryCompression
      && new Date(message.createdAt ?? '').getTime() <= time)
    const anchorMessage = following ?? preceding[preceding.length - 1] ?? tools[tools.length - 1]
      ?? earlier[earlier.length - 1] ?? candidates[0]!
    const anchor = result.indexOf(anchorMessage)
    const message = result[anchor]!
    message.artifacts = [...(message.artifacts ?? []), artifact]
  }
  return result
}
