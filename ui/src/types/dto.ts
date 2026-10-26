/**
 * DTO类型定义
 *
 */

import type { PageParams } from './common'
import type { HookType, ToolType, ModelType, KbType, McpProtocol, TenantRole } from './enums'

/**
 * 账号查询DTO
 */
export interface AccountDTO extends PageParams {
  nickname?: string
  email?: string
  username?: string
  enabled?: boolean
}

/**
 * 智能体定义查询DTO
 */
export interface AgentDefinitionDTO extends PageParams {
  name?: string
  agentType?: 'CUSTOM' | 'A2A'
  agentCode?: string
  enabled?: boolean
  tag?: string
  /** 可见性范围：personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=全部可见 */
  scope?: string
  space?: string
}

/**
 * Hook配置查询DTO
 */
export interface HookConfigDTO extends PageParams {
  name?: string
  hookType?: HookType
  enabled?: boolean
}

/**
 * 知识库配置查询DTO
 */
export interface KnowledgeBaseConfigDTO extends PageParams {
  name?: string
  kbType?: KbType
  enabled?: boolean
  /** 可见性范围：personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=全部可见 */
  scope?: string
  space?: string
}

/**
 * MCP服务器查询DTO
 */
export interface McpServerDTO extends PageParams {
  name?: string
  protocol?: McpProtocol
  enabled?: boolean
  /** 可见性范围：personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=全部可见 */
  scope?: string
  space?: string
}

/**
 * 模型配置查询DTO
 */
export interface ModelConfigDTO extends PageParams {
  providerId?: string
  name?: string
  enabled?: boolean
}

/**
 * 模型提供商查询DTO
 */
export interface ModelProviderDTO extends PageParams {
  name?: string
  type?: string
  enabled?: boolean
}

/**
 * 敏感词配置查询DTO
 */
export interface SensitiveWordConfigDTO extends PageParams {
  category?: string
  name?: string
  enabled?: boolean
}

/**
 * 技能包查询DTO
 */
export interface SkillPackageDTO extends PageParams {
  name?: string
  category?: string
  enabled?: boolean
  /** 可见性范围：personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=全部可见 */
  scope?: string
  space?: string
}

/**
 * 本地导入技能包配置
 */
export interface LocalImportConfig {
  /** 技能分类 */
  category: string
  /** 本地路径 */
  path: string
  /** 是否覆盖已存在的同名技能 */
  cover: boolean
}

/**
 * Git导入技能包配置
 */
export interface GitImportConfig {
  /** 技能分类 */
  category: string
  /** 仓库地址 */
  repoUrl: string
  /** 是否覆盖已存在的同名技能 */
  cover: boolean
}

/**
 * 系统提示词模板查询DTO
 */
export interface SystemPromptTemplateDTO extends PageParams {
  category?: string
  name?: string
  enabled?: boolean
}

/**
 * 工具查询DTO
 */
export interface ToolDTO extends PageParams {
  name?: string
  toolId?: string
  toolType?: ToolType
  category?: string
  enabled?: boolean
  /** 可见性范围：personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=全部可见 */
  scope?: string
  space?: string
}

/**
 * 修改密码请求
 */
export interface ChangePasswordRequest {
  /**
   * 旧密码（明文密码的 MD5）
   *
   * 用户自助改密时必填；「首次登录强制改密」时可省略（此时账号带着
   * mustChangePassword 标记，服务端不再要求重复输入管理员分配的密码）。
   */
  oldPassword?: string
  newPassword: string
}

/**
 * 登录请求
 */
export interface LoginRequest {
  username: string
  password: string
  /** 租户ID（多租户用户选择租户登录时携带） */
  tenantId?: string
}

/**
 * 用户详情（登录返回）
 */
export interface UserDetail {
  id: string
  name: string
  username: string
  email: string
  /** 平台组织ID（内部实现细节，「部门即组织」后前端不再展示组织切换） */
  tenantId?: string | null
  /** 平台组织编码 */
  tenantCode?: string | null
  /** 已废弃：account_tenant 旧枚举角色 */
  tenantRole?: string | null
  /** 平台组织名称 */
  tenantName?: string | null
  /** 所属部门（组织）ID；为空表示未归属任何组织 */
  deptId?: string | number | null
  /** 所属部门（组织）名称 */
  deptName?: string | null
  /** 已废弃：多组织已下线，恒为单条 */
  tenants?: TenantInfo[]
}

/**
 * 组织（部门）简要信息
 */
export interface TenantInfo {
  tenantId: string
  tenantCode: string
  tenantName: string
  role: string
}

/**
 * 登录响应
 */
export interface LoginResponse {
  accessToken: string
  accessTokenTTL: string
  refreshToken: string
  refreshTokenTTL: string
  userDetail: UserDetail
  /** 是否需要选择租户 */
  needSelectTenant?: boolean
  /** 可选择的租户列表 */
  tenants?: TenantInfo[]
  /** 是否必须先修改密码（管理员新增用户 / 重置密码后为 true） */
  mustChangePassword?: boolean
}

/**
 * 刷新Token请求
 */
export interface RefreshTokenRequest {
  refreshToken: string
}

/**
 * 管理员新增用户请求
 *
 * 系统已关闭自助注册，账号只能由拥有 system:user:add 的管理员创建。
 * 「部门即组织」后，`deptId` 决定新账号归属的组织（部门），留空表示无组织账号。
 */
export interface AccountCreateRequest {
  /** 昵称 */
  nickname: string
  /** 登录账号 */
  username: string
  /** 邮箱（可选，填写时需全局唯一） */
  email?: string
  /** 归属部门（组织）ID；留空表示不归属任何组织 */
  deptId?: string | number | null
  /** 初始密码（留空则由服务端随机生成） */
  password?: string
}

/**
 * 账号初始/重置密码结果
 *
 * 服务端只保存摘要，明文密码仅在「服务端随机生成」时于该响应中返回一次。
 */
export interface AccountCredentialVO {
  id: string
  username: string
  nickname: string
  /** 明文密码（仅当 generated = true 时有值） */
  password?: string
  /** 密码是否由服务端随机生成；false 表示管理员自行指定，服务端不留明文 */
  generated: boolean
  /** 用户首次登录后是否必须修改密码 */
  mustChangePassword: boolean
}

/**
 * 更新用户信息请求
 */
export interface UpdateProfileRequest {
  nickname?: string
  email?: string
  rememberLastTenant?: boolean
}

/**
 * 创建会话DTO
 */
export interface ChatSessionCreateDTO {
  agentId: string
  title?: string
  initWorkspace?: boolean
}

/**
 * 追加消息DTO（正常对话或重新生成）
 */
export interface ChatMessageAppendDTO {
  role: string
  content: string
}

/**
 * 租户查询DTO
 */
export interface TenantDTO extends PageParams {
  name?: string
  code?: string
  status?: number
  enabled?: boolean
}

/**
 * 选择租户请求
 */
export interface SelectTenantRequest {
  tenantId: string
}

/**
 * 租户创建/更新请求
 */
export interface TenantCreateRequest {
  name: string
  code: string
  description?: string
  contactName?: string
  contactEmail?: string
}

/**
 * 组织基本设置请求
 */
export interface TenantSettingsRequest {
  name?: string
  description?: string
  contactName?: string
  contactEmail?: string
}

/**
 * 会话列表查询DTO
 */
export interface ChatSessionQueryDTO {
  userId?: string
  agentId?: string
  isPinned?: boolean
  /**
   * 只看游客会话（分享链接产生，归属人不是平台账号）
   *
   * 需要 agent:chat:guest:manage 权限点，后端会二次判权。
   * 注意：userId 已被后端忽略（防越权列他人会话），跨用户查看只能走这个开关。
   */
  guestOnly?: boolean
  /** Explicit management view; omitted means the caller's own conversations. */
  scope?: 'mine' | 'team' | 'all'
  page?: number
  size?: number
}

/**
 * OpenAPI 导入 DTO
 */
export interface OpenApiImportDTO {
  /** OpenAPI 文档内容（JSON 或 YAML 文本） */
  spec: string
  /** 选中的 operationId 列表；为空表示导入全部 */
  operationIds?: string[]
  /** 覆盖文档中的服务地址 */
  baseUrl?: string
  /** 导入后的工具分类 */
  category?: string
  /** 统一附加的鉴权请求头名称 */
  authHeaderName?: string
  /** 统一附加的鉴权请求头取值 */
  authHeaderValue?: string
}
