/**
 * 系统设置树形面板的共享类型与注入键
 *
 */
import type { Component, InjectionKey, Ref } from 'vue'

/** 树节点（部门与菜单的公共子集） */
export interface SettingsTreeNode {
  id: string | number
  name: string
  children?: SettingsTreeNode[]
  /** 右侧计数徽标（如该部门直属人数） */
  badge?: number
}

/** 树节点行内操作按钮（由使用方按权限决定是否提供） */
export interface SettingsTreeAction {
  /** 操作标识，回传给 `@action` */
  key: string
  /** 悬浮提示（图标按钮没有文案，必须给出说明） */
  title: string
  icon: Component
  danger?: boolean
}

/** 递归节点需要的上下文 */
export interface SettingsTreeContext {
  selected: Ref<string | number | null>
  select: (id: string | number | null) => void
  /** 行内操作按钮配置（空数组表示该树不展示操作） */
  actions: Ref<SettingsTreeAction[]>
  /** 触发行内操作 */
  runAction: (key: string, node: SettingsTreeNode) => void
}

export const SETTINGS_TREE_CTX: InjectionKey<SettingsTreeContext> = Symbol('settings-tree-ctx')
