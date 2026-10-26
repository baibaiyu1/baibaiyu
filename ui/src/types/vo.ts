import type { ResourceVisibilityVO } from '@/api/resource'
/**
 * VO类型定义
 *
 */

import type {
  ToolChoiceStrategy,
  HookType,
  ToolType,
  AuthType,
  ModelType,
  HealthStatus,
  KbType,
  McpActivationStatus,
  McpFailureSource,
  McpToolExposureMode,
  McpMode,
  McpProtocol,
  SensitiveWordAction,
  RAGMode,
  TenantRole
} from './enums'
import type { AgentA2A, HookConfig, JobInfo } from '@/types/entity.ts'
import type { SubAgentRunVO } from './subagent'

/**
 * 租户VO
 */
export interface TenantVO {
  id: string | number
  name: string
  code: string
  description: string
  status: number
  contactName: string
  contactEmail: string
  config: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

/**
 * 账号VO
 */
export interface AccountVO {
  id: string | number
  nickname: string
  email: string
  username: string
  enabled: boolean
  /** 已废弃：account_tenant 旧枚举角色，不再返回 */
  tenantRole?: string
  createdAt?: string
  updatedAt?: string
  createdBy?: string
  updatedBy?: string
  rememberLastTenant?: boolean
  /** 是否必须先修改密码（管理员新增用户 / 重置密码后为 true） */
  mustChangePassword?: boolean
  /** 所属部门（组织）ID；为空表示未归属任何组织 */
  deptId?: string | number
  /** 所属部门（组织）名称 */
  deptName?: string
  /** 已分配的角色ID */
  roleIds?: Array<string | number>
  /** 已分配的角色名称 */
  roleNames?: string[]
}

/**
 * 智能体对话端展示配置（功能增强 · Web App 用户体验）
 */
export interface AgentUiConfig {
  /** 是否在对话输入区显示计划模式入口，未配置时保持显示 */
  showPlanMode?: boolean
  /** 是否在对话中显示实际模型用量，未配置时保持显示 */
  showModelUsage?: boolean
  /** 开场白 */
  openingMessage?: string
  /** 推荐问题（最多 6 条，顺序即展示顺序） */
  openingQuestions?: string[]
}

/**
 * 智能体定义VO
 */
export interface AgentDefinitionVO extends ResourceVisibilityVO {
  id: string | number
  agentType: 'CUSTOM' | 'A2A'
  name: string
  agentCode: string
  description: string
  modelConfigId: string
  modelParamsOverride: Record<string, unknown> | null
  reasoningConfig?: import('./reasoning').ReasoningSettings | null
  modelResilienceConfig?: { maxAttempts: number; fallbackModelId?: string; summaryModelId?: string } | null
  ragConfig: Record<string, unknown> | null
  skill: string[]
  workflow: string[]
  tool: string[]
  mcp: string[]
  mcpBindings: AgentMcpBindingVO[]
  hook: string[]
  subAgent: string[]
  knowledgeBase: string[]
  toolChoiceStrategy: ToolChoiceStrategy
  specificToolName: string
  systemPromptTemplateId: string
  followTemplate: boolean
  systemPrompt: string
  sensitiveWordConfigId: string
  sensitiveFilterEnabled: boolean
  maxIterations: number
  enablePlanning: boolean
  maxSubtasks: number
  requirePlanConfirmation: boolean
  enableMemory: boolean
  enableMemoryCompression: boolean
  showToolProcess: boolean
  memoryCompressionConfig: Record<string, unknown> | null
  structuredOutputEnabled: boolean
  structuredOutputSchema: Record<string, unknown> | null
  structuredOutputReminder: 'PROMPT' | 'TOOL_CHOICE'
  version: string
  tag: string | null
  tags?: string[]
  /** 对话端展示配置（功能增强 · Web App 用户体验）：开场白 / 推荐问题 */
  uiConfig: AgentUiConfig | null
  enabled: boolean
  /** Publication is independent of internal visibility and runtime enablement. */
  published?: boolean
  /** Administrator-approved guest access through a share link. */
  externalShareEnabled?: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
  agentA2A: AgentA2A
  jobInfo: JobInfo
  studioConfigId: string | null
  codeExecutionConfigId: string | null
  longTermMemoryConfigId: string | null
  /** 可见性：PRIVATE=仅创建者可见，TENANT=本组织可见 */
  visibility?: string | null
  /** 归属人账号ID */
  ownerId?: string | number | null
  resourceDeptId?: string | number | null
  canManage?: boolean
  canChangeVisibility?: boolean
  canUse?: boolean
  /** Fork 来源资源ID */
  forkFromId?: string | number | null
  /** Fork 根资源ID */
  forkRootId?: string | number | null
}

/**
 * Hook配置VO
 */
export interface HookConfigVO extends HookConfig{
  used: string[]
}

/**
 * 知识库配置VO
 */
export interface KnowledgeBaseConfigVO extends ResourceVisibilityVO {
  id: string | number
  name: string
  kbType: KbType
  ragMode: RAGMode
  description: string
  /** 可见性：PRIVATE=仅自己可见，TENANT=本组织可见（列表接口不返回，由前端补读） */
  visibility?: string | null
  connectionConfig: Record<string, unknown> | null
  endpointConfig: Record<string, unknown> | null
  retrievalConfig: Record<string, unknown> | null
  rerankingConfig: Record<string, unknown> | null
  queryRewriteConfig: Record<string, unknown> | null
  metadataFilters: Record<string, unknown> | null
  httpConfig: Record<string, unknown> | null
  healthStatus: HealthStatus
  lastSyncTime: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * MCP服务器VO
 */
export interface McpServerVO extends ResourceVisibilityVO {
  id: string | number
  name: string
  protocol: McpProtocol
  mode: McpMode
  timeout: number
  protocolConfig: Record<string, unknown> | null
  description: string
  /** 可见性：PRIVATE=仅自己可见，TENANT=本组织可见（列表接口不返回，由前端补读） */
  visibility?: string | null
  healthStatus: HealthStatus
  lastHealthCheck: string
  activationStatus: McpActivationStatus
  activationMessage: string
  failureSource: McpFailureSource
  activationStatusChangedAt: string | null
  lastActivationTime: string | null
  lastToolSyncTime: string | null
  toolCount: number
  availableToolCount: number
  runtimeFailThreshold: number
  needsSync: boolean
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * Agent MCP 绑定信息
 */
export interface AgentMcpBindingVO {
  mcpServerId: string
  exposureMode: McpToolExposureMode
  mcpToolIds: string[]
}

/**
 * MCP 工具 VO
 */
export interface McpToolVO {
  id: string | number
  mcpServerId: string | number
  toolName: string
  description: string
  inputSchema: Record<string, unknown> | null
  outputSchema: Record<string, unknown> | null
  enabled: boolean
  needConfirm: boolean
  missing: boolean
  sort: number
  lastDiscoveredAt: string | null
  lastSeenAt: string | null
}

/**
 * MCP 工具调试调用结果
 */
export interface McpToolDebugResultVO {
  success: boolean
  toolName: string
  content: unknown
  errorMessage: string | null
  durationMs: number
  executedAt: string
}

/**
 * MCP 工具调试历史记录项
 */
export interface McpDebugHistoryItem {
  id: string
  toolId: string | number
  toolName: string
  serverName: string
  input: Record<string, unknown>
  result: McpToolDebugResultVO
  executedAt: string
}

/**
 * 模型配置VO
 */
export interface ModelConfigVO {
  reasoningConfig?: import('./reasoning').ReasoningSettings
  reasoningCapabilities?: import('./reasoning').ReasoningCapabilities
  id: string | number
  providerId: string
  name: string
  modelId: string
  apiType?: 'CHAT_COMPLETIONS' | 'RESPONSES'
  responsesSampling?: boolean
  modelType: ModelType[]
  description: string
  streaming: boolean
  thinking: boolean
  contextWindow: number
  maxTokens: number
  temperature: number
  topP: number
  topK: number
  repeatPenalty: number
  seed: string
  extendConfig: Record<string, any> | null
  connectivityStatus?: string
  connectivityMessage?: string
  lastConnectivityCheck?: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * 模型提供商VO
 */
export interface ModelProviderVO {
  id: string | number
  type: string
  name: string
  description: string
  baseUrl: string
  authType: AuthType
  apiKey: string
  envVarName: string
  enabled: boolean
  configMeta: Record<string, unknown> | null
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

/**
 * 敏感词配置VO
 */
export interface SensitiveWordConfigVO {
  id: string | number
  category: string
  name: string
  description: string
  words: string[] | null
  action: SensitiveWordAction
  replacement: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * 技能包VO
 */
export interface SkillPackageVO extends ResourceVisibilityVO {
  id: string | number
  name: string
  alias?: string
  description: string
  category: string
  /** 可见性：PRIVATE=仅自己可见，TENANT=本组织可见（列表接口不返回，由前端补读） */
  visibility?: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
  tools:string[]
}

/**
 * 技能包文件树节点
 */
export interface SkillFileTreeNode {
  /** 文件名或目录名 */
  name: string
  /** 相对路径，如 "scripts/helper.py" */
  path: string
  /** 是否目录 */
  directory: boolean
  /** DB id（入库文件才有，纯文件系统文件为 null） */
  fileId: string | null
  /** 文件类型（仅入库文件） */
  fileType: 'SKILL_MD' | 'REFERENCES' | 'EXAMPLES' | 'SCRIPTS' | null
  /** 文件扩展名，目录为空 */
  extension: string
  /** 文件大小（字节），目录为 0 */
  fileSize: number
  /** 子节点 */
  children: SkillFileTreeNode[]
  /** 文件内容（前端加载后填充） */
  content?: string
  /** 前端状态：是否有未保存修改 */
  dirty?: boolean
}

/**
 * 技能包导入结果
 */
export interface SkillImportResult {
  importedCount: number
  skippedCount: number
  totalCount: number
  hintMessage?: string | null
}

/**
 * SkillHub 技能市场VO
 */
export interface SkillsHubVO {
  category: string
  slug: string
  description: string
  createdAt: string
  updatedAt: string
  version: string
  name: string
  downloads: string
  homepage: string
  iconUrl: string
  requiresApiKey: string
}

/**
 * 系统提示词模板VO
 */
export interface SystemPromptTemplateVO {
  id: string | number
  category: string
  name: string
  description: string
  content: string
  usageCount: number
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * 工具VO
 */
export interface ToolVO extends ResourceVisibilityVO {
  id: string | number
  name: string
  toolId: string
  description: string
  category: string
  language: string
  toolType: ToolType
  /** 可见性：PRIVATE=仅自己可见，TENANT=本组织可见（列表接口不返回，由前端补读） */
  visibility?: string | null
  inputSchema: any[] | null
  outputSchema: any[] | Record<string, unknown> | null
  classPath: string
  code: string
  needConfirm: boolean,
  returnDirect?: boolean
  version: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * 聊天会话VO
 */
export interface ChatSessionVO {
  id: string | number
  userId: string
  agentId: string
  currentMessageId: string | null
  title: string | null
  isPinned: boolean
  pinTime: string | null
  messageTable: string | null
  /** 是否为游客会话（归属人不是平台账号，来源是分享链接） */
  isGuest: boolean
  /** Server-computed capabilities for session and workspace operations. */
  canRead?: boolean
  canWrite?: boolean
  ownerDeptId?: string | number | null
  createdAt: string
  updatedAt: string
}

/**
 * 聊天消息VO
 */
export interface ChatMessageVO {
  id: string | number
  sessionId: string
  role: string
  content: string
  parentId: string | null
  path: string
  depth: number
  createdAt: string
  subAgentRun?: SubAgentRunVO | null
}

/**
 * 对话产物VO —— 智能体实际写出/修改的工作空间文件
 */
export interface ChatArtifactVO {
  id: string | number
  sessionId: string
  /** 工作空间内相对路径，用于调工作空间预览 */
  filePath: string
  fileName: string
  extension: string | null
  sizeBytes: number | null
  /** 产出该文件的工具名 */
  sourceTool: string | null
  /** 最近一次写入时间；前端据此把产物挂到此前最近的一条消息上 */
  createTime: string
}

/**
 * Studio配置VO
 */
export interface StudioConfigVO {
  id: string
  url: string
  project: string
}

/**
 * 访问秘钥VO
 */
export interface SecretKeyVO {
  id: string | number
  name: string
  /** value 在列表中已脱敏，创建时返回完整值 */
  value: string
  expireTime: string | null
  remark: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

/**
 * 代码执行配置VO
 */
export interface CodeExecutionConfigVO {
  id: string
  configName: string
  workDir?: string
  uploadDir?: string
  autoUpload?: boolean
  enableShell?: boolean
  enableRead?: boolean
  enableWrite?: boolean
  command?: string[] | null
}

/**
 * 长期记忆配置VO
 */
export interface LongTermMemoryConfigVO {
  id: string
  configName: string
  memoryType: 'MEM0' | 'REME' | 'BAILIAN'
  config: Record<string, unknown> | null
  enabled: boolean
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  used: string[]
}

/**
 * 趋势数据项
 */
export interface TrendItem {
  date: string
  value: number
}

/**
 * 智能体统计分析VO
 */
export interface AgentStatisticsVO {
  sessionTrend: TrendItem[]
  activeUserTrend: TrendItem[]
  messageTrend: TrendItem[]
  avgRoundsTrend: TrendItem[]
}

/**
 * 工作空间文件树节点 VO
 */
export interface WorkspaceFileNode {
  /** 文件或文件夹名称 */
  name: string
  /** 文件在工作空间中的相对路径 */
  path: string
  /** 是否为目录 */
  directory: boolean
  /** 文件全名（仅文件有效，含后缀） */
  fullName?: string
  /** 文件后缀（仅文件有效，不含点号） */
  extension?: string
  /** 文件大小可读格式（仅文件有效，如 "1.5 MB"） */
  readableSize?: string
  /** 最后修改时间（格式：yyyy-MM-dd HH:mm:ss） */
  lastModified?: string
  /** 最后修改时间戳（毫秒） */
  lastModifiedTime?: string
  /** 子节点（仅目录有效） */
  children?: WorkspaceFileNode[]
}

/**
 * 工作空间容量信息 VO
 */
export interface WorkspaceCapacityVO {
  /** 已使用空间（字节） */
  usedBytes: number
  /** 最大容量（字节） */
  maxBytes: number
  /** 已使用空间可读格式 */
  usedReadable: string
  /** 最大容量可读格式 */
  maxReadable: string
  /** 使用百分比（0~100） */
  percent: number
}

/**
 *  模型检查结果
 */
export interface CheckModelResult {
  success: boolean
  message: string
}

/** 服务状态信息 */
export interface ServiceStatusInfo {
  serviceType: 'FILE' | 'PROXY' | 'RUNTIME' | 'GATEWAY'
  status: 'UP' | 'DOWN'
  port: number | null
  lastHeartbeat: string
  startedAt: string
}

/** 执行节点状态 VO */
export interface NodeStatusVO {
  nodeId: string
  hostname: string
  ip: string
  firstSeenAt: string
  lastUpdatedAt: string
  services: ServiceStatusInfo[]
  nodeStatus: 'HEALTHY' | 'DEGRADED' | 'DOWN'
}

/** WebSocket 消息服务节点状态 VO */
export interface WebSocketNodeVO {
  nodeId: string
  hostname: string
  ip: string
  port: number | null
  status: 'UP' | 'DOWN'
  firstSeenAt: string
  lastUpdatedAt: string
  startedAt: string
  lastHeartbeat: string
}

/** OpenAPI 导入 - 单个接口操作预览 */
export interface OpenApiOperationVO {
  /** 文档中的原始 operationId */
  operationId?: string
  /** 生成的工具编号 */
  toolId: string
  method: string
  path: string
  summary?: string
  tag?: string
  /** 参数个数 */
  paramCount?: number
  /** 是否包含请求体 */
  hasBody?: boolean
  /** 是否已存在同名工具（导入时按更新处理） */
  exists?: boolean
}

/** OpenAPI 导入 - 文档解析预览 */
export interface OpenApiPreviewVO {
  title?: string
  version?: string
  specVersion?: string
  servers?: string[]
  baseUrl?: string
  operations: OpenApiOperationVO[]
}

/** 知识库来源字典项 */
export interface KnowledgeSourceVO {
  /** 来源标识（对应 KbType 枚举名，如 LOCAL / BAILIAN / DIFY / RAGFLOW） */
  value: string
  /** 来源名称 */
  label: string
  /** 来源说明 */
  description?: string
  /** 是否支持在平台内上传文档（仅本地类型支持） */
  uploadSupported?: boolean
}
