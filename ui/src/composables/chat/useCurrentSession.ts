import { ref, watch } from 'vue'
import * as chatSessionApi from '@/api/chatSession'
import type { ChatMessageVO, ChatSessionVO } from '@/types'

/** 每次向上加载的消息条数 */
const PAGE_SIZE = 30

export function useCurrentSession(agentId: import('vue').Ref<string>) {
  const currentSessionMessageTable = ref<string | null>(null)
  const currentSessionId = ref<string | null>(null)
  const currentSessionTitle = ref<string>('')
  const messagesList = ref<ChatMessageVO[]>([])

  /** selectSession/resetSession 已自行加载消息时置 true，阻止 watcher 重复加载 */
  const loadingMessages = ref(false)

  /** 是否还有更早的历史消息 */
  const hasMoreHistory = ref(false)
  /** 加载中状态 */
  const historyLoading = ref(false)
  /** 下一页游标（本批最小 depth） */
  const nextBeforeDepth = ref<number | null>(null)
  let messageRequestVersion = 0

  /**
   * 首次加载：分页获取最新 PAGE_SIZE 条消息
   */
  const loadCurrentMessages = async (preserveMessages = false) => {
    const sid = currentSessionId.value
    const version = ++messageRequestVersion
    if (!sid) {
      messagesList.value = []
      hasMoreHistory.value = false
      nextBeforeDepth.value = null
      return
    }
    try {
      if (!preserveMessages) messagesList.value = []
      const res = await chatSessionApi.getCurrentMessagesPaged(
        sid,
        { beforeDepth: null, size: PAGE_SIZE }
      )
      if (version !== messageRequestVersion || currentSessionId.value !== sid) return
      const page = res.data?.data
      messagesList.value = (page?.messages ?? []) as ChatMessageVO[]
      hasMoreHistory.value = page?.hasMore ?? false
      nextBeforeDepth.value = page?.nextBeforeDepth ?? null
    } catch (cause) {
      if (version !== messageRequestVersion || currentSessionId.value !== sid) return
      if (preserveMessages) throw cause
      messagesList.value = []
      hasMoreHistory.value = false
      nextBeforeDepth.value = null
    }
  }

  /**
   * 加载更早的历史消息，返回新加载的条数
   */
  const loadMoreHistory = async (): Promise<number> => {
    if (!currentSessionId.value || !hasMoreHistory.value || historyLoading.value) {
      return 0
    }
    historyLoading.value = true
    const sid = currentSessionId.value
    const version = messageRequestVersion
    try {
      const res = await chatSessionApi.getCurrentMessagesPaged(
        currentSessionId.value,
        { beforeDepth: nextBeforeDepth.value, size: PAGE_SIZE }
      )
      if (version !== messageRequestVersion || currentSessionId.value !== sid) return 0
      const page = res.data?.data
      const older = (page?.messages ?? []) as ChatMessageVO[]
      if (older.length > 0) {
        // 追加到列表头部，保持顺序不变
        messagesList.value = [...older, ...messagesList.value]
      }
      hasMoreHistory.value = page?.hasMore ?? false
      nextBeforeDepth.value = page?.nextBeforeDepth ?? null
      return older.length
    } catch {
      return 0
    } finally {
      historyLoading.value = false
    }
  }

  const selectSession = async (session: ChatSessionVO) => {
    loadingMessages.value = true
    currentSessionId.value = String(session.id)
    currentSessionTitle.value = session.title || '新对话'
    currentSessionMessageTable.value = session.messageTable || null
    try {
      await loadCurrentMessages()
    } finally {
      loadingMessages.value = false
    }
  }

  const resetSession = (session: ChatSessionVO | null = null) => {
    messageRequestVersion++
    loadingMessages.value = true
    if (session == null) {
      currentSessionId.value = null
      currentSessionTitle.value = ''
      currentSessionMessageTable.value = null
    } else {
      currentSessionId.value = String(session.id)
      currentSessionTitle.value = session.title || ''
      currentSessionMessageTable.value = session.messageTable || null
    }
    messagesList.value = []
    hasMoreHistory.value = false
    nextBeforeDepth.value = null
    loadingMessages.value = false
  }

  // 同步观察切换，才能在 selectSession/resetSession 的保护区间内跳过自动查询。
  watch(currentSessionId, async () => {
    if (loadingMessages.value) return
    if (currentSessionId.value) await loadCurrentMessages()
    else {
      messagesList.value = []
      hasMoreHistory.value = false
      nextBeforeDepth.value = null
    }
  }, { flush: 'sync' })

  return {
    currentSessionId,
    currentSessionTitle,
    currentSessionMessageTable,
    messagesList,
    hasMoreHistory,
    historyLoading,
    loadingMessages,
    loadCurrentMessages,
    loadMoreHistory,
    selectSession,
    resetSession,
  }
}
