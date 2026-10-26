/**
 * 对话页 UI 状态 Store
 *
 * 管理侧边栏折叠、工作区宽度等按 agent+account 隔离的 UI 偏好，
 * 使用 Pinia 持久化到 localStorage。
 *
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'chat_agent_preferences'

/** 单个 agent 的 UI 偏好 */
export interface AgentPreference {
  sidebarCollapsed?: boolean
  /** 工作空间（右栏）宽度，像素。未设置时用 DEFAULT_WORKSPACE_PANEL_WIDTH */
  workspacePanelWidth?: number
}

/** 工作空间右栏默认宽度 */
export const DEFAULT_WORKSPACE_PANEL_WIDTH = 720

/** 工作空间右栏宽度下限（再窄预览就没意义了） */
export const MIN_WORKSPACE_PANEL_WIDTH = 360

/** 工作空间右栏宽度上限 */
export const MAX_WORKSPACE_PANEL_WIDTH = 1200

/** 偏好映射：key 为 `${agentId}_${accountId}` */
type PreferenceMap = Record<string, AgentPreference>

function preferenceKey(agentId: string, accountId: string | undefined): string {
  return `${agentId}_${accountId ?? 'anonymous'}`
}

export const useChatStore = defineStore(
  'chat',
  () => {
    const preferences = ref<PreferenceMap>({})

    /**
     * 获取侧边栏折叠状态
     */
    function getSidebarCollapsed(
      agentId: string,
      accountId: string | undefined
    ): boolean {
      if (!agentId) return false
      const key = preferenceKey(agentId, accountId)
      return preferences.value[key]?.sidebarCollapsed ?? true
    }

    /**
     * 设置侧边栏折叠状态
     */
    function setSidebarCollapsed(
      agentId: string,
      accountId: string | undefined,
      value: boolean
    ): void {
      if (!agentId) return
      const key = preferenceKey(agentId, accountId)
      if (!preferences.value[key]) preferences.value[key] = {}
      preferences.value[key].sidebarCollapsed = value
    }

    /**
     * 获取工作空间右栏宽度
     *
     * 越界值一律回落到默认宽度，避免历史脏数据或手工改 localStorage 把布局撑坏。
     */
    function getWorkspacePanelWidth(
      agentId: string,
      accountId: string | undefined
    ): number {
      if (!agentId) return DEFAULT_WORKSPACE_PANEL_WIDTH
      const key = preferenceKey(agentId, accountId)
      const stored = preferences.value[key]?.workspacePanelWidth
      if (typeof stored !== 'number' || !Number.isFinite(stored)) {
        return DEFAULT_WORKSPACE_PANEL_WIDTH
      }
      if (stored < MIN_WORKSPACE_PANEL_WIDTH || stored > MAX_WORKSPACE_PANEL_WIDTH) {
        return DEFAULT_WORKSPACE_PANEL_WIDTH
      }
      return stored
    }

    /**
     * 设置工作空间右栏宽度（写入前夹到合法区间）
     */
    function setWorkspacePanelWidth(
      agentId: string,
      accountId: string | undefined,
      value: number
    ): void {
      if (!agentId) return
      const clamped = Math.min(
        MAX_WORKSPACE_PANEL_WIDTH,
        Math.max(MIN_WORKSPACE_PANEL_WIDTH, Math.round(value))
      )
      const key = preferenceKey(agentId, accountId)
      if (!preferences.value[key]) preferences.value[key] = {}
      preferences.value[key].workspacePanelWidth = clamped
    }

    return {
      preferences,
      getSidebarCollapsed,
      setSidebarCollapsed,
      getWorkspacePanelWidth,
      setWorkspacePanelWidth
    }
  },
  {
    persist: {
      key: STORAGE_KEY,
      storage: localStorage,
      pick: ['preferences'],
      debug: import.meta.env.DEV,
    },
  }
)
