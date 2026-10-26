import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentClient } from '@/composables/useAgentClient'
import { usePlanTracking } from '@/composables/chat/usePlanTracking'
import { useSubAgentRuns } from '@/composables/chat/useSubAgentRuns'
import { buildToolCallsContent } from '@/utils/chat/format'
import type {ChatArtifactVO, ChatMessageVO, RawEvent, ContextUsageEvent, ContextCompressionEvent, ToolResultImage} from '@/types'
import { useAccountStore } from '@/stores'
import { getRunStatus, stopRun } from '@/api/agui'
import type { ChatSpeechEvent } from '@/utils/chat/voice-text'

export function useChatStream(
  agentId: import('vue').Ref<string>,
  agentDetail: import('vue').Ref<any>,
  currentSessionId: import('vue').Ref<string | null>,
  fileIds?: import('vue').Ref<string[]>,
  memoryActive?: import('vue').Ref<boolean>,
  planActive?: import('vue').Ref<boolean>,
  toolProcessActive?: import('vue').Ref<boolean>,
  onMessageSaved?: (chatMsg: ChatMessageVO) => void,
  planMode?: import('vue').Ref<boolean>,
  onRecoverHistory?: (threadId: string, running: boolean) => Promise<void>,
  onSpeechEvent?: (event: ChatSpeechEvent) => void) {
  // 服务端在普通运行、审批续跑和队列运行开始时发布实际选项。
  const runToolProcessActive = ref<boolean | null>(null)
  const showToolDetails = computed(() => runToolProcessActive.value ?? toolProcessActive?.value ?? true)

  const { userInfo } = useAccountStore()
  const { runs: subAgentRuns, acceptCustomEvent, reset: resetSubAgentRuns } = useSubAgentRuns()

  // 计划追踪
  const {
    currentPlan,
    hasPlan,
    onToolStart: onPlanToolStart,
    onToolArgs: onPlanToolArgs,
    onToolResult: onPlanToolResult,
    resetPlan
  } = usePlanTracking()

  const getForwardedProps = () => ({
    agentId: agentId.value,
    agentCode: agentDetail.value?.agentCode,
    fileIds: fileIds?.value ?? [],
    memoryActive: memoryActive?.value ?? false,
    planActive: planActive?.value ?? false,
    planMode: planMode?.value ?? false,
    toolProcessActive: toolProcessActive?.value ?? false,
    userInfo: userInfo
  })

  // 流式内容
  const agentHasResult = ref(true)
  const streamingMessageId = ref<string | null>(null)
  const streamingRole = ref<'user' | 'assistant' | 'system' | 'tool' | 'thinking'>('system')
  const streamingContent = ref('')
  const contextUsage = ref<ContextUsageEvent['value'] | null>(null)
  const memoryCompressionActive = ref(false)
  const isStopping = ref(false)
  let stopRequestId = 0
  let compressionNoticeTimer: ReturnType<typeof setTimeout> | null = null
  let speechRunId = '', speechSessionId = ''
  const speechOffsets = new Map<string, number>()
  const speechRoles = new Map<string, string>()
  const speechEvent = (type: 'start' | 'finish' | 'cancel', replay = false) => {
    if (speechRunId && speechSessionId === currentSessionId.value) {
      onSpeechEvent?.({ type, sessionId: speechSessionId, runId: speechRunId, replay })
    }
  }
  const speechText = (messageId: string, text: string, final = false) => {
    const offset = speechOffsets.get(messageId) ?? 0
    speechOffsets.set(messageId, text.length)
    if (!speechRunId || speechSessionId !== currentSessionId.value || speechRoles.get(messageId) !== 'assistant') return
    onSpeechEvent?.({ type: 'text', sessionId: speechSessionId, runId: speechRunId, messageId,
      text, delta: text.slice(offset), final, replay: client.isReplaying || isStopping.value })
  }

  const saveStreamedMessage = (id: string, role: 'assistant' | 'thinking', content: string) => {
    const sid = currentSessionId.value
    if (!sid || !content) return
    onMessageSaved?.({
      id,
      sessionId: sid,
      role,
      content,
      parentId: '',
      path: '',
      depth: 0,
      createdAt: ''
    })
  }

  const clearStreamingMessage = () => {
    streamingMessageId.value = null
    streamingContent.value = ''
    streamingRole.value = 'system'
  }

  /** 等待后端确认 Agent 真正结束，避免停止请求返回后立即复用仍在运行的 Agent。 */
  const waitForRunStopped = async (sessionId: string): Promise<boolean> => {
    while (true) {
      try {
        const status = await getRunStatus(sessionId)
        if (!status.running || status.state === 'COMPLETED') return true
      } catch {
        // 短暂网络错误不改变停止状态，下一轮继续查询。
      }
      await new Promise(resolve => setTimeout(resolve, 500))
    }
  }

  // 工具调用进度
  const toolCallsInProgress = ref<
    Array<{ id: string; name: string; args: string; result?: string; startTime: number; elapsed?: number,
      needConfirm?: boolean; images?: ToolResultImage[]; artifacts?: ChatArtifactVO[];
      citations?: import('@/types/chat').KnowledgeCitation[]; hideDetails?: boolean }>
  >([])

  // HITL：逐工具确认决策（toolUseId → 状态），所有项决策完即调 /agui/resume
  const pendingConfirms = ref<Record<string, 'pending' | 'approved' | 'rejected'>>({})

  /**
   * HITL：根据待确认列表重建确认 UI（标记/新建工具项 + 建立逐工具决策态）。两条来源共用：
   * - 实时 TOOL_CONFIRM_REQUIRED 事件：工具项已由 ToolCallStart 建立，只需标记 needConfirm；
   * - 刷新/重进会话（GET /agui/pending 恢复）：toolCallsInProgress 已被清空，须按 input 新建工具项，
   *   否则没有任何工具项承载「允许/禁止」按钮，暂停态卡死无法续点。
   * @param pending 待确认工具 [{toolUseId,name,input}]
   */
  const restorePending = (
    pending: Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
  ) => {
    if (!pending || pending.length === 0) return
    const ids = new Set(pending.map(p => p.toolUseId))
    // 已存在的标记 needConfirm
    const arr = toolCallsInProgress.value.map(t => (ids.has(t.id) ? { ...t, needConfirm: true } : t))
    // 缺失的新建（刷新场景）
    const existing = new Set(arr.map(t => t.id))
    pending.forEach(p => {
      if (!existing.has(p.toolUseId)) {
        const args = p.input && Object.keys(p.input).length ? JSON.stringify(p.input) : '{}'
        arr.push({ id: p.toolUseId, name: p.name, args, needConfirm: true, startTime: Date.now() })
      }
    })
    toolCallsInProgress.value = arr
    const next: Record<string, 'pending' | 'approved' | 'rejected'> = { ...pendingConfirms.value }
    pending.forEach(p => { next[p.toolUseId] = 'pending' })
    pendingConfirms.value = next
  }

  // 使用原有的 useAgentClient
  const { messages, isRunning, isReplaying, run, abort, disconnect, reconnect, resume, addUserMessage, client } = useAgentClient({
    onRecoverHistory: onRecoverHistory ? async (threadId, running) => {
      if (currentSessionId.value !== threadId) return
      resetStreamingState()
      await onRecoverHistory(threadId, running)
    } : undefined,
    handlers: {
      onRunStarted: (e) => {
        speechRunId = e.runId; speechSessionId = e.threadId
        speechOffsets.clear(); speechRoles.clear()
        speechEvent('start', client.isReplaying)
        runToolProcessActive.value = null
        toolCallsInProgress.value = []
        streamingContent.value = ''
        streamingMessageId.value = null
        contextUsage.value = null
        memoryCompressionActive.value = false
        if (compressionNoticeTimer) {
          clearTimeout(compressionNoticeTimer)
          compressionNoticeTimer = null
        }
        resetSubAgentRuns()
      },
      onTextMessageStart: (e) => {
        speechRoles.set(e.messageId, e.role)
        speechOffsets.set(e.messageId, 0)
        streamingRole.value = 'assistant'
        streamingContent.value = ''
        streamingMessageId.value = e.messageId
      },
      onTextMessageContent: (_e, currentText) => {
        speechText(_e.messageId, currentText)
        agentHasResult.value = true
        streamingContent.value = currentText
      },
      onTextMessageEnd: (e, finalText) => {
        speechText(e.messageId, finalText, true)
        saveStreamedMessage(e.messageId, 'assistant', finalText)
        // 结束事件只清理对应的流式消息，避免清空随后开始的推理或正文。
        if (streamingRole.value === 'assistant' && streamingMessageId.value === e.messageId) {
          clearStreamingMessage()
        }
      },
      onReasoningMessageStart: (e) => {
        streamingRole.value = 'thinking'
        streamingContent.value = ''
        // 后端的推理和正文可能共用 messageId，独立气泡必须使用不同的前端 ID。
        streamingMessageId.value = `thinking:${e.messageId}`
      },
      onReasoningMessageContent: (_e, currentText) => {
        streamingContent.value = currentText
      },
      onReasoningMessageEnd: (e, finalText) => {
        const messageId = `thinking:${e.messageId}`
        // 使用该事件自己的推理缓冲，迟到或重复的推理结束事件不能读走正在显示的正文。
        saveStreamedMessage(messageId, 'thinking', finalText)
        if (streamingRole.value === 'thinking' && streamingMessageId.value === messageId) {
          clearStreamingMessage()
        }
      },
      onToolCallStart: (e) => {
        // 计划追踪：记录工具调用名称
        onPlanToolStart(e.toolCallId, e.toolCallName)

        agentHasResult.value = true
        toolCallsInProgress.value = [
          ...toolCallsInProgress.value.filter(tool => tool.id !== e.toolCallId),
          { id: e.toolCallId, name: e.toolCallName, args: '', startTime: Date.now() }
        ]

        const sid = currentSessionId.value
        if (sid && streamingContent.value) {
          // 推理结束时保存为独立消息
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value,
            content: streamingContent.value,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
          // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
          streamingMessageId.value = null
          streamingContent.value = ''
          streamingRole.value = 'system'
        }
      },
      onToolCallArgs: (_e, partialArgs) => {
        // 计划追踪：累积工具参数
        onPlanToolArgs(_e.toolCallId, partialArgs)

        const arr = [...toolCallsInProgress.value]
        const tool = arr.find(tool => tool.id === _e.toolCallId)
        if (tool) tool.args = partialArgs
        toolCallsInProgress.value = arr
      },
      onToolCallResult: (e) => {
        // 计划追踪：处理工具结果
        onPlanToolResult(e.toolCallId, e.content)

        try {
          // 判断是否开启了显示工具调用
          if (!showToolDetails.value) {
            return
          }
          // 更新工具调用结果和耗时
          toolCallsInProgress.value = toolCallsInProgress.value.map((t) =>
            t.id === e.toolCallId ? { ...t, result: e.content, elapsed: Date.now() - t.startTime } : t
          )

          // 保存当前完成的工具调用消息（仅保存刚完成的这一条）
          const sid = currentSessionId.value
          if (sid) {
            const completedTool = toolCallsInProgress.value.find((t) => t.id === e.toolCallId)
            if (completedTool) {
              const contentToSave = buildToolCallsContent([completedTool])
              if (contentToSave) {
                onMessageSaved?.({
                  id: `tool:${e.toolCallId}`,
                  sessionId: sid,
                  role: 'tool',
                  content: contentToSave,
                  parentId: '',
                  path: '',
                  depth: 0,
                  createdAt: ''
                } as ChatMessageVO)
              }
            }
          }
        } finally {
          // 移除已完成的工具调用，保留仍在进行中的
          toolCallsInProgress.value = toolCallsInProgress.value.filter((t) => t.id !== e.toolCallId)
        }
      },
      onRunFinished: (_e) => {
        speechEvent('finish', client.isReplaying)
        agentHasResult.value = true
        toolCallsInProgress.value = toolCallsInProgress.value.filter(tool => tool.needConfirm)
        // 不再全标记 needConfirm（旧 Bug1/MCP 假象根源）；
        // 确认改由 onCustom 的 TOOL_CONFIRM_REQUIRED 事件精确驱动
      },
      onRunError: (event) => {
        speechEvent('cancel')
        agentHasResult.value = true
        toolCallsInProgress.value = toolCallsInProgress.value.filter(tool => tool.needConfirm)
        if (event.code !== 'REPLAY_GAP') {
          message.error(event.message || '对话运行失败，请重试')
        }
      },
      onRaw: (event) => {
        const e = event as RawEvent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const rawEvent: any = e.rawEvent
        if(rawEvent.error) {
          streamingMessageId.value = new Date().getTime() + '' + Math.floor(Math.random() * 90000) + 10000
          streamingContent.value = rawEvent.error
          const sid = currentSessionId.value
          if (sid) {
            onMessageSaved?.({
              id: streamingMessageId.value,
              sessionId: sid,
              role: 'error',
              content: rawEvent.error,
              parentId: '',
              path: '',
              depth: 0,
              createdAt: ''
            } as ChatMessageVO)
            // 保存完成后清除推理状态，利用 displayMessages 去重避免闪烁
            streamingMessageId.value = null
            streamingContent.value = ''
            streamingRole.value = 'system'
          }
        }
     },
      onCustom: (event) => {
        if (event.name === 'KNOWLEDGE_RETRIEVAL') {
          const value = event.value as { toolCallId?: string; status?: string }
          if (!value?.toolCallId) return
          if (value.status === 'STARTED' && !toolCallsInProgress.value.some(tool => tool.id === value.toolCallId)) {
            agentHasResult.value = true
            toolCallsInProgress.value.push({ id: value.toolCallId, name: 'retrieve_knowledge', args: '', startTime: Date.now() })
          } else if (value.status === 'FINISHED') {
            toolCallsInProgress.value = toolCallsInProgress.value.filter(tool => tool.id !== value.toolCallId)
          }
          return
        }
        if (event.name === 'CHAT_RUN_OPTIONS') {
          const value = event.value as { toolProcessActive?: boolean }
          if (typeof value?.toolProcessActive === 'boolean') runToolProcessActive.value = value.toolProcessActive
          return
        }
        if (event.name === 'TOOL_RESULT_CONTENT') {
          const value = event.value as { toolCallId?: string; toolName?: string; text?: string;
            images?: ToolResultImage[]; artifacts?: ChatArtifactVO[]; citations?: import('@/types/chat').KnowledgeCitation[] }
          const sid = currentSessionId.value
          if (!sid || !value?.toolCallId || (!Array.isArray(value.images) && !Array.isArray(value.artifacts) && !Array.isArray(value.citations))) return
          const tool = toolCallsInProgress.value.find(item => item.id === value.toolCallId)
          const completed = {
            id: value.toolCallId,
            name: value.toolName || tool?.name || '工具',
            args: showToolDetails.value ? tool?.args ?? '' : '',
            result: value.text ?? '',
            elapsed: tool ? Date.now() - tool.startTime : 0,
            images: value.images ?? [],
            citations: value.citations ?? [],
            artifacts: (value.artifacts ?? []).map(artifact => ({ ...artifact, sessionId: sid, id: artifact.filePath })),
            hideDetails: !showToolDetails.value,
          }
          if (tool) Object.assign(tool, completed)
          agentHasResult.value = true
          onMessageSaved?.({
            id: `tool:${value.toolCallId}`, sessionId: sid, role: 'tool',
            content: buildToolCallsContent([completed]), parentId: '', path: '', depth: 0, createdAt: '',
          })
          return
        }
        if (event.name === 'CONTEXT_USAGE') {
          contextUsage.value = (event as ContextUsageEvent).value
          return
        }
        if (event.name === 'CONTEXT_COMPRESSION') {
          const compressionEvent = event as ContextCompressionEvent
          contextUsage.value = {
            ...contextUsage.value,
            ...compressionEvent.value,
            ratio: compressionEvent.value.compressionPressure
          } as ContextUsageEvent['value']
          if (compressionNoticeTimer) {
            clearTimeout(compressionNoticeTimer)
            compressionNoticeTimer = null
          }
          memoryCompressionActive.value = compressionEvent.value.status === 'STARTED'
          if (compressionEvent.value.status !== 'STARTED') {
            compressionNoticeTimer = setTimeout(() => {
              memoryCompressionActive.value = false
              compressionNoticeTimer = null
            }, 800)
          }
          return
        }
        // Keep sub-agent CUSTOM events out of the normal text/tool message buffers.
        if (acceptCustomEvent(event)) {
          const trace = event.value as { eventType?: string; invocationId?: string }
          if (trace.eventType === 'STARTED' && trace.invocationId) {
            // The parent tool call is represented by the dedicated card, not a second generic row.
            toolCallsInProgress.value = toolCallsInProgress.value.filter((tool) => tool.id !== trace.invocationId)
            onPlanToolResult(trace.invocationId)
          }
          return
        }
        // HITL：收到 TOOL_CONFIRM_REQUIRED 时，精确标记需确认的工具（不再全标记）
        if (event.name === 'TOOL_CONFIRM_REQUIRED') {
          const pending = (((event.value as any)?.pending) ?? []) as Array<{ toolUseId: string; name: string; input?: Record<string, unknown> }>
          restorePending(pending)
        }
      }
    },
    onRecoveryFailure: (error) => {
      agentHasResult.value = true
      console.warn('对话流恢复失败', error)
      message.error(error.message.includes('刷新会话')
        ? error.message : '对话连接中断，未能自动恢复，请刷新会话')
    }
  })

  /**
   * HITL：记录单个工具的确认决策（替代旧的「前端代执行/塞文本 + run 重开一轮」）。
   * 所有待确认工具都决策后，调用 /agui/resume 由后端从暂停点续跑。
   * @param toolUseId 工具调用 id（= TOOL_CONFIRM_REQUIRED 的 toolUseId）
   * @param approved true=允许，false=拒绝
   */
  const decideConfirm = (toolUseId: string, approved: boolean) => {
    if (pendingConfirms.value[toolUseId] === undefined) return
    pendingConfirms.value = {
      ...pendingConfirms.value,
      [toolUseId]: approved ? 'approved' : 'rejected'
    }
    // 该工具按钮收起（已决策）
    toolCallsInProgress.value = toolCallsInProgress.value.map(t =>
      t.id === toolUseId ? { ...t, needConfirm: false } : t
    )
    // 所有待确认工具都已决策 → 提交 resume
    const states = Object.values(pendingConfirms.value)
    if (states.length > 0 && states.every(s => s !== 'pending')) {
      void submitResume()
    }
  }

  /** 汇总逐工具决策并调用后端 resume，续接 SSE 流。 */
  const submitResume = async () => {
    const sid = currentSessionId.value
    if (!sid) return
    const decisions = Object.entries(pendingConfirms.value).map(([toolUseId, s]) => {
      const t = toolCallsInProgress.value.find(x => x.id === toolUseId)
      return { toolUseId, name: t?.name ?? '', approved: s === 'approved' }
    })
    pendingConfirms.value = {}
    agentHasResult.value = false
    await resume(sid, decisions, memoryActive?.value ?? false)
  }

  // 中止运行
  const abortRun = async () => {
    if (isStopping.value || !isRunning.value) return
    speechEvent('cancel')
    const sid = currentSessionId.value
    isStopping.value = true
    const requestId = ++stopRequestId
    let stoppedConfirmed = !sid
    try {
      // 先通知后端进入 STOPPING，再断开当前浏览器 SSE 连接。
      if (sid) {
        try { await stopRun(sid) } catch { /* 状态轮询仍会继续确认后端结果 */ }
      }
      await abort()
      agentHasResult.value = true

      // 重置计划状态
      resetPlan()

      if (sid) {
        // 保存所有进行中的工具调用消息（按顺序逐个保存）
        if (toolCallsInProgress.value.length > 0) {
          for (const tool of toolCallsInProgress.value) {
            if (!showToolDetails.value && !tool.images?.length && !tool.artifacts?.length && !tool.citations?.length) continue
            const contentToSave = buildToolCallsContent([{ ...tool,
              args: showToolDetails.value ? tool.args : '', hideDetails: !showToolDetails.value }])
            if (contentToSave) {
              onMessageSaved?.({
                id: `tool:${tool.id}`,
                sessionId: sid,
                role: 'tool',
                content: contentToSave,
                parentId: '',
                path: '',
                depth: 0,
                createdAt: ''
              } as ChatMessageVO)
            }
          }
        }
        // 保存 AI 回复消息
        else if (streamingContent.value) {
          onMessageSaved?.({
            id: streamingMessageId.value,
            sessionId: sid,
            role: streamingRole.value,
            content: streamingContent.value,
            parentId: '',
            path: '',
            depth: 0,
            createdAt: ''
          } as ChatMessageVO)
        }
      }

      // 只有后端确认 Agent 真正结束后，才清理思考、工具和压缩临时状态。
      stoppedConfirmed = sid ? await waitForRunStopped(sid) : true
    } finally {
      // 会话切换后旧停止请求的轮询不能清理新会话的界面状态。
      if (requestId !== stopRequestId || currentSessionId.value !== sid) return
      toolCallsInProgress.value = []
      pendingConfirms.value = {}
      resetSubAgentRuns()
      streamingMessageId.value = null
      streamingContent.value = ''
      streamingRole.value = 'system'
      memoryCompressionActive.value = false
      if (compressionNoticeTimer) {
        clearTimeout(compressionNoticeTimer)
        compressionNoticeTimer = null
      }
      // 超时后继续保持停止中状态，防止后端仍占用 Agent 时再次发送请求。
      if (stoppedConfirmed) isStopping.value = false
    }
  }

  // 发送消息（可选传入 fileIds 覆盖，用于发送时已清空输入框的场景）
  const sendMessage = async (
    inputText: string,
    messagesList: ChatMessageVO[],
    overrideFileIds?: string[]
  ) => {
    const effectiveFileIds = overrideFileIds ?? fileIds?.value ?? []
    if (!agentId.value) return
    if (!inputText.trim() && !effectiveFileIds.length) return
    if (isRunning.value || isStopping.value) return
    if (!agentDetail.value?.agentCode) {
      message.error('智能体信息未加载完成，请稍后再试')
      return
    }

    // 构建 client 需要的消息格式
    client.messages = messagesList
      .filter((m) => !['system', 'tool', 'subagent'].includes(m.role))
      .map((m) => ({
        id: String(m.id),
        role: m.role as any,
        content: (m.content || '') as string
      }))

    const forwardedProps = getForwardedProps()
    if (overrideFileIds !== undefined) {
      forwardedProps.fileIds = overrideFileIds
    }

    agentHasResult.value = false
    await run({
      threadId: currentSessionId.value || undefined,
      runId: `run_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`,
      forwardedProps
    })
  }

  /**
   * 重置所有流式状态，用于会话切换时清理旧 session 残留。
   */
  function resetStreamingState() {
    speechEvent('cancel')
    speechRunId = ''; speechSessionId = ''
    speechOffsets.clear(); speechRoles.clear()
    stopRequestId++
    runToolProcessActive.value = null
    isStopping.value = false
    streamingMessageId.value = null
    streamingContent.value = ''
    streamingRole.value = 'system'
    toolCallsInProgress.value = []
    pendingConfirms.value = {}
    resetSubAgentRuns()
    agentHasResult.value = true
    currentPlan.value = null
    contextUsage.value = null
    memoryCompressionActive.value = false
    if (compressionNoticeTimer) {
      clearTimeout(compressionNoticeTimer)
      compressionNoticeTimer = null
    }
  }

  return {
    agentHasResult,
    streamingContent,
    streamingMessageId,
    streamingRole,
    toolCallsInProgress: computed(() => toolCallsInProgress.value.filter(tool => showToolDetails.value
      || tool.needConfirm || tool.name === 'retrieve_knowledge')),
    subAgentRuns,
    isRunning,
    isStopping,
    isReplaying,
    currentPlan,
    contextUsage,
    memoryCompressionActive,
    hasPlan,
    abortRun,
    sendMessage,
    decideConfirm,
    pendingConfirms,
    restorePending,
    reconnect,
    disconnect,
    resetStreamingState,
    client, // 如果需要暴露
  }
}
