/**
 * 类型定义统一导出
 *
 */

// 通用类型
export * from './common'

// 架构类型
export * from './layout'

// 枚举类型
export * from './enums'

// Entity类型
export * from './entity'

// VO类型
export * from './vo'

// DTO类型
export * from './dto'

// AGUI 协议类型
export * from './agui'

// Chat 类型
export * from './chat'

export * from './subagent'

export * from './chat-mention'

// Plan 类型
export * from './plan'

export * from './workflow'

// Workflow resource maintenance types
export * from './workflowResources'

// 资源发布审批类型
export * from './approval'

// 说明：系统设置相关类型（部门/角色/菜单/用户列表）位于 './system'，
// 因 `Role` 与历史遗留的 './enums' 同名，故不在此桶文件导出，
// 使用方请直接 `import type { ... } from '@/types/system'`。
