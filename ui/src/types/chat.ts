// 扩展消息类型用于展示（含流式标记）
export interface ToolResultImage {
  filePath: string | null
  fileName: string
  extension: string | null
  mimeType: string | null
  sizeBytes: number | null
  error: string | null
  sourceTool?: string
  /** 归一化前的图片地址，仅用于把正文引用关联到不可变副本。 */
  sourcePath?: string | null
}

export type ChatImageSource =
  | { kind: 'workspace'; sessionId: string; path: string }
  | { kind: 'attachment'; id: string }
  | { kind: 'url'; url: string }

/** 所有对话图片共用的展示引用，文件读取仍沿用各自的鉴权接口。 */
export interface ChatImage {
  key: string
  source: ChatImageSource | null
  fileName: string
  extension: string | null
  sizeBytes: number | null
  sourceTool?: string | null
  error?: string | null
  aliases?: string[]
}

export interface ToolResultContent {
  toolCallId?: string
  name: string
  totalTimes: number
  args: string
  result: string
  images?: ToolResultImage[]
  artifacts?: import('./vo').ChatArtifactVO[]
  citations?: KnowledgeCitation[]
  hideDetails?: boolean
}

export interface KnowledgeCitation {
  knowledgeId: string
  knowledgeName: string
  referenceId: string
  fileName: string
  filePath: string
  content: string
}

/** 当前可见回复分支内的引用编号，不写回原始消息。 */
export interface KnowledgeCitationReference extends KnowledgeCitation {
  number: number
  anchorId: string
  cited: boolean
}

export interface DisplayMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool' | 'thinking' | 'subagent'
  content: string
  createdAt?: string
  isStreaming?: boolean
  /** 仅前端显示的上下文压缩提示，不会持久化。 */
  isMemoryCompression?: boolean
  /** 回复分支：当前消息在兄弟分支中的下标（0 起） */
  branchIndex?: number | null
  /** 回复分支：兄弟分支总数，>1 时展示切换控件 */
  branchTotal?: number
  /** 是否允许对该回复触发「重新生成」 */
  canRegenerate?: boolean
  subAgentRun?: import('./subagent').SubAgentRunVO | null
  /**
   * 该条回复产出/修改的工作空间文件。
   *
   * 新记录由工具结果携带稳定关联；旧记录按时间与来源工具定位。
   */
  artifacts?: import('./vo').ChatArtifactVO[]
}

/**
 * 会话消息分支信息
 *
 * 同一父消息下的多个候选回复（由「重新生成」产生），用于前后切换分支。
 */
export interface MessageBranchVO {
  /** 当前路径上的消息ID */
  messageId: number
  /** 父消息ID */
  parentId: number | null
  /** 兄弟消息ID列表（升序，含自身） */
  siblingIds: number[]
  /** 当前消息在兄弟列表中的下标 */
  currentIndex: number
  /** 兄弟总数（可选分支数） */
  total: number
}
