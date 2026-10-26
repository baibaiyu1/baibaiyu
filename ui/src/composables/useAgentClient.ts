/**
 * Vue 组合式 API：封装 AgentClient，提供响应式 messages/state/activity 与 run/abort 方法
 * 便于在对话页直接绑定并调用
 */

import { ref, onUnmounted } from 'vue'
import { createAgentClient, getReconnectURL, getResumeURL, getRunStatus } from '@/api/agui'
import type { Message, RunAgentInput } from '@/types'
import type { EventHandlers, ToolHandler } from '@/api/agui'

export interface UseAgentClientOptions {
  url?: string
  headers?: Record<string, string>
  /** 额外事件回调（在内部 sync 之后调用） */
  handlers?: EventHandlers
  /** SSE 无法恢复时通知页面。 */
  onRecoveryFailure?: (error: Error) => void
  /** 事件窗口失效后补查持久历史；运行未结束时持续补查，不能重发原请求。 */
  onRecoverHistory?: (threadId: string, running: boolean) => Promise<void>
  toolHandlers?: Record<string, ToolHandler>
}

/**
 * 使用 AGUI AgentClient，状态与 client 同步为响应式
 * @param options 可选 URL、headers、handlers、toolHandlers
 * @returns 响应式状态与方法
 */
export function useAgentClient(options: UseAgentClientOptions = {}) {
  const messages = ref<Message[]>([])
  const state = ref<unknown>({})
  const activityMessages = ref<Record<string, Message>>({})
  const isRunning = ref(false)
  const isReplaying = ref(false)
  const error = ref<Error | null>(null)
  let connectionVersion = 0
  const MAX_RECOVERY_FAILURES = 5

  const retryDelay = (attempt: number) =>
    new Promise(resolve => setTimeout(resolve, Math.min(1000 * 2 ** Math.min(attempt, 3), 8000)))

  const syncFromClient = (client: ReturnType<typeof createAgentClient>) => {
    messages.value = [...client.messages]
    state.value = client.state
    activityMessages.value = { ...client.activityMessages }
  }

  const client = createAgentClient({
    ...options,
    handlers: {
      onEvent: (e) => {
        syncFromClient(client)
        options.handlers?.onEvent?.(e)
      },
      onRunStarted: (e) => {
        syncFromClient(client)
        options.handlers?.onRunStarted?.(e)
      },
      onRunFinished: (e) => {
        error.value = null
        syncFromClient(client)
        options.handlers?.onRunFinished?.(e)
      },
      onRunError: (e) => {
        error.value = new Error(e.message)
        if (e.code === 'REPLAY_GAP') return
        options.handlers?.onRunError?.(e)
      },
      onTextMessageStart: (e) => {
        options.handlers?.onTextMessageStart?.(e)
        syncFromClient(client)
      },
      onTextMessageContent: (e, text) => {
        options.handlers?.onTextMessageContent?.(e, text)
        syncFromClient(client)
      },
      onTextMessageEnd: (e, text) => {
        options.handlers?.onTextMessageEnd?.(e, text)
        syncFromClient(client)
      },
      onToolCallStart: options.handlers?.onToolCallStart,
      onToolCallArgs: options.handlers?.onToolCallArgs,
      onToolCallEnd: options.handlers?.onToolCallEnd,
      onToolCallResult: options.handlers?.onToolCallResult,
      onStateSnapshot: options.handlers?.onStateSnapshot,
      onStateDelta: options.handlers?.onStateDelta,
      onMessagesSnapshot: options.handlers?.onMessagesSnapshot,
      onActivitySnapshot: options.handlers?.onActivitySnapshot,
      onActivityDelta: options.handlers?.onActivityDelta,
      onCustom: options.handlers?.onCustom,
      onRaw: options.handlers?.onRaw,
      onReasoningMessageStart: options.handlers?.onReasoningMessageStart,
      onReasoningMessageContent: options.handlers?.onReasoningMessageContent,
      onReasoningMessageEnd: options.handlers?.onReasoningMessageEnd
    },
    toolHandlers: options.toolHandlers
  })

  async function consumeRun(threadId: string | undefined, connect: () => Promise<void>) {
    const version = ++connectionVersion
    isRunning.value = true
    error.value = null
    const failRecovery = (failure: Error) => {
      error.value = failure
      if (version === connectionVersion) options.onRecoveryFailure?.(failure)
    }
    const recoverHistory = async () => {
      if (!threadId || !options.onRecoverHistory) {
        failRecovery(error.value ?? new Error('对话连接中断，未能自动恢复，请刷新会话'))
        return
      }
      isReplaying.value = false
      let failures = 0
      while (version === connectionVersion) {
        try {
          const { running } = await getRunStatus(threadId)
          if (version !== connectionVersion) return
          await options.onRecoverHistory(threadId, running)
          if (version !== connectionVersion) return
          error.value = null
          if (!running) return
          failures = 0
        } catch (cause) {
          const failure = cause instanceof Error ? cause : new Error(String(cause))
          if (version !== connectionVersion) return
          if (/Status check failed: (401|403|404)/.test(failure.message)
              || ++failures >= MAX_RECOVERY_FAILURES) {
            failRecovery(failure)
            return
          }
        }
        await retryDelay(Math.max(1, failures - 1))
      }
    }
    try {
      try {
        await connect()
      } catch (cause) {
        error.value = cause instanceof Error ? cause : new Error(String(cause))
      }
      if (version !== connectionVersion) return
      if (client.replayGapMessage) { await recoverHistory(); return }
      if (client.hasTerminalEvent) return
      if (!threadId) {
        failRecovery(error.value ?? new Error('对话连接已中断，无法查询运行状态'))
        return
      }

      // A proxy or browser may close the SSE connection while the Agent is still running.
      // Query the run before reconnecting so the original request is never submitted twice.
      let failures = 0
      while (version === connectionVersion) {
        let running: boolean
        try {
          running = (await getRunStatus(threadId)).running
        } catch (cause) {
          error.value = cause instanceof Error ? cause : new Error(String(cause))
          if (version !== connectionVersion) return
          if (/Status check failed: (401|403|404)/.test(error.value.message)
              || ++failures >= MAX_RECOVERY_FAILURES) {
            failRecovery(error.value)
            return
          }
          await retryDelay(failures - 1)
          continue
        }
        if (version !== connectionVersion) return
        try {
          error.value = null
          await client.reconnect(getReconnectURL(threadId))
        } catch (cause) {
          error.value = cause instanceof Error ? cause : new Error(String(cause))
          if (version !== connectionVersion) return
          if (!running && options.onRecoverHistory && !/Reconnect HTTP (401|403)/.test(error.value.message)) {
            await recoverHistory()
            return
          }
          if (++failures >= MAX_RECOVERY_FAILURES || !running) {
            failRecovery(error.value)
            return
          }
          await retryDelay(failures - 1)
          continue
        }
        if (version !== connectionVersion) return
        if (client.replayGapMessage) { await recoverHistory(); return }
        if (client.hasTerminalEvent) return
        if (!running) {
          await recoverHistory()
          return
        }
        failures = 0
        await retryDelay(0)
      }
    } finally {
      if (version === connectionVersion) {
        isRunning.value = false
        isReplaying.value = client.isReplaying
        syncFromClient(client)
      }
    }
  }

  async function run(overrides?: Partial<RunAgentInput>) {
    await consumeRun(overrides?.threadId, () => client.run(overrides))
  }

  async function abort() {
    connectionVersion++
    await client.abort()
    isRunning.value = false
    isReplaying.value = false
  }

  /**
   * 仅断开 SSE 连接，不停止后端 Agent（用于会话切换时清理旧连接）
   */
  function disconnect() {
    connectionVersion++
    client.abort()
    isRunning.value = false
    isReplaying.value = false
  }

  /**
   * SSE 重连到运行中的 Agent：回放已缓冲事件并接续实时流
   * @param threadId 会话 ID
   */
  async function reconnect(threadId: string, freshRun = false) {
    isReplaying.value = true
    await consumeRun(threadId, () => client.reconnect(getReconnectURL(threadId), freshRun))
  }

  /**
   * HITL resume：提交逐工具确认决策并续接 SSE 流
   * @param threadId 会话 ID
   * @param decisions 逐工具决策（toolUseId/name/approved）
   * @param memoryActive 是否开启记忆
   */
  async function resume(
    threadId: string,
    decisions: Array<{ toolUseId: string; name: string; approved: boolean }>,
    memoryActive: boolean
  ) {
    await consumeRun(threadId, () =>
      client.resume(getResumeURL(threadId), { decisions, memoryActive }))
  }

  function addUserMessage(content: string, id?: string) {
    const msg = client.addUserMessage(content, id)
    messages.value = [...client.messages]
    return msg
  }

  // 组件卸载时仅断开 SSE 连接，不中断后台 Agent
  onUnmounted(() => {
    connectionVersion++
    client.abort()
  })

  return {
    messages,
    state,
    activityMessages,
    isRunning,
    isReplaying,
    error,
    run,
    abort,
    disconnect,
    reconnect,
    resume,
    addUserMessage,
    /** 需直接操作 client 时使用（如 set messages/state） */
    client
  }
}
