<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import { useRoute } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { useAccountStore, useChatStore } from '@/stores'
import { formatSessionTitle } from '@/utils/chat/format'
import { useAgentDetail } from '@/composables/chat/useAgentDetail'
import { useSessions } from '@/composables/chat/useSessions'
import { useCurrentSession } from '@/composables/chat/useCurrentSession'
import { useChatStream } from '@/composables/chat/useChatStream'
import { useChatArtifacts } from '@/composables/chat/useChatArtifacts'
import { useConversationQueue } from '@/composables/chat/useConversationQueue'
import { useVoiceConversation } from '@/composables/chat/useVoiceConversation'
import { submitInstruction } from '@/api/agui/instructions'
import { enqueueMessage } from '@/api/agui/queue'
import type { QueueSnapshot } from '@/api/agui/queue'
import ChatSidebar from '@/components/chat/ChatSidebar.vue'
import ChatMain from '@/components/chat/ChatMain.vue'
import VoiceConversation from '@/components/chat/VoiceConversation.vue'
import RenameModal from '@/components/chat/RenameModal.vue'
import WorkspacePanel from '@/components/workspace/WorkspacePanel.vue'
import type { DisplayMessage, ChatMessageVO, UploadedFileItem, ChatSessionVO, MessageBranchVO, ChatArtifactVO } from '@/types'
import type { FlatFileItem } from '@/composables/chat/useWorkspaceFiles'
import { WS_MESSAGE_TYPES } from '@/websocket/const/websocket'
import { useWebSocketEvent } from '@/websocket/composables/useWebSocketEvent'
import { attachArtifacts } from '@/utils/chat/artifact'
import * as chatSessionApi from '@/api/chatSession'
import { getActiveRuns, getStatus, getPending, stopRun } from '@/api/agui'
import { LoadingOutlined } from '@ant-design/icons-vue'
import {
  buildUserTextFromPayload,
  injectSubmissionToRawContent
} from '@/utils/chat/uip.ts'
import type { InteractionSubmitPayload } from '@/components/markdown/uip/types'

const props = withDefaults(defineProps<{
  showAccount: boolean
  chatAgentId: string | null | undefined
  shareIdentity?: { id: string; nickname: string }
}>(), {
  showAccount: true,
  chatAgentId: null
})

const route = useRoute()
const accountStore = useAccountStore()
const chatStore = useChatStore()
const userInfo = computed(() => props.shareIdentity ?? accountStore.userInfo)

const agentId = computed(() => (props.chatAgentId || route.params.agentId) as string || '')

// 智能体详情
const { agentDetail, allowFileType } = useAgentDetail(agentId)

// 运行选项统一跟随智能体设置，本地偏好仅保存布局。
const accountId = computed(() => props.shareIdentity?.id ?? accountStore.userInfo?.id)
const memoryActive = computed(() => agentDetail.value?.enableMemory === true)
const planActive = computed(() => agentDetail.value?.enablePlanning === true)
const toolProcessActive = computed(() => agentDetail.value?.showToolProcess === true)
// 是否配置了代码执行
const hasCodeExecutionConfig = computed(() => agentDetail.value?.codeExecutionConfigId)

const sidebarCollapsed = computed({
  get: () => {
    const id = agentDetail.value?.id ?? agentId.value
    chatStore.preferences
    return chatStore.getSidebarCollapsed(id as string, accountId.value as string)
  },
  set: (v: boolean) => {
    const id = agentDetail.value?.id ?? agentId.value
    chatStore.setSidebarCollapsed(id as string, accountId.value as string, v)
  },
})

// 会话列表管理
const {
  pinnedSessions,
  otherSessions,
  loading: sessionsLoading,
  hasMore: sessionsHasMore,
  createSession,
  updateSessionTitle,
  pinSession,
  unpinSession,
  deleteSession,
  clearAgentSessions,
  loadSessions,
  loadMoreSessions,
} = useSessions(agentId)

// 当前会话管理
const {
  currentSessionId,
  currentSessionTitle,
  currentSessionMessageTable,
  messagesList,
  hasMoreHistory,
  historyLoading,
  selectSession,
  resetSession,
  loadMoreHistory,
  loadCurrentMessages,
  loadingMessages
} = useCurrentSession(agentId)

// 已上传附件（仅已完成上传的计入 fileIds）
const uploadedFiles = ref<UploadedFileItem[]>([])
const fileIds = computed(() =>
  uploadedFiles.value.filter((f) => !f.uploading).map((f) => f.id)
)

// 流式对话及工具调用
const planMode = ref(false)
const canViewPlan = computed(() => !props.shareIdentity && accountStore.hasPerm('agent:chat:instruct')
  && !currentSessionMessageTable.value && agentDetail.value?.agentType === 'CUSTOM')
const allowPlanMode = computed(() => canViewPlan.value && agentDetail.value?.uiConfig?.showPlanMode !== false)
watch(currentSessionId, (_next, previous) => { if (previous) planMode.value = false })
watch(agentId, () => { planMode.value = false })
watch(allowPlanMode, allowed => { if (!allowed) planMode.value = false })
const {
  agentHasResult,
  streamingContent,
  streamingMessageId,
  streamingRole,
  toolCallsInProgress,
  subAgentRuns,
  isRunning,
  isStopping,
  currentPlan,
  contextUsage,
  memoryCompressionActive,
  sendMessage,
  decideConfirm,
  restorePending,
  abortRun,
  reconnect: reconnectStream,
  disconnect: disconnectStream,
  resetStreamingState,
} = useChatStream(
  agentId,
  agentDetail,
  currentSessionId,
  fileIds,
  memoryActive,
  planActive,
  toolProcessActive,
  (chatMsg: ChatMessageVO) => {
    if (String(chatMsg.sessionId) !== currentSessionId.value) return
    const existing = messagesList.value.find(item => String(item.id) === String(chatMsg.id))
    if (existing) Object.assign(existing, chatMsg)
    else messagesList.value.push(chatMsg)
  }, planMode, async (sid, running) => {
    if (currentSessionId.value !== sid) return
    await loadCurrentMessages(true)
    if (currentSessionId.value === sid && !running) await restoreConfirm(sid)
  }, event => voice.businessEvent(event))

const allowRunningMessages = computed(() => !props.shareIdentity && accountStore.hasPerm('agent:chat:instruct') && !currentSessionMessageTable.value)
let connectedQueuedRun: string | null = null
let queueSyncBusy = false
const handleQueueSnapshot = async (value: QueueSnapshot) => {
  const sid = currentSessionId.value
  const withRuns = value.messages.filter(m => m.runId && m.state !== 'STEERED')
  const latest = withRuns[withRuns.length - 1]
  if (!sid || !latest?.runId || connectedQueuedRun === latest.runId || isRunning.value || isStopping.value || queueSyncBusy) return
  queueSyncBusy = true
  try {
    const running = ['RUNNING', 'STOPPING'].includes(value.state) && value.runId === latest.runId
    // Queue claims a run before the SSE tracker is registered. Attaching in
    // that gap falls back to history polling and loses all live speech.
    if (running) {
      let ready = false
      for (let attempt = 0; attempt < 6 && currentSessionId.value === sid; attempt++) {
        try { ready = await getStatus(sid) } catch { return }
        if (ready) break
        await new Promise(resolve => setTimeout(resolve, 100))
      }
      if (!ready) return
    }
    if (currentSessionId.value !== sid) return
    connectedQueuedRun = latest.runId
    disconnectStream()
    resetStreamingState()
    await loadCurrentMessages()
    if (currentSessionId.value !== sid) return
    if (running) void reconnectStream(sid, true)
    else void restoreConfirm(sid)
  } finally { queueSyncBusy = false }
}
const conversationQueue = useConversationQueue(currentSessionId, allowRunningMessages, value => { void handleQueueSnapshot(value) })
const { snapshot: queueSnapshot, error: queueError, sending: queueSending } = conversationQueue
watch(currentSessionId, () => { connectedQueuedRun = null })

// 语音对话体验待完善，暂时关闭入口及可用性探测。
const voiceConversationEnabled = false
const allowVoiceConversation = computed(() => voiceConversationEnabled && allowRunningMessages.value)
const voice = useVoiceConversation(currentSessionId, allowVoiceConversation, {
  ensureSession: async () => {
    if (!currentSessionId.value) {
      if (isSubmitting.value) throw new Error('会话正在创建，请稍后再连接语音')
      const creatingForAgent = agentId.value
      isSubmitting.value = true
      try {
        const session = await createSession('语音对话')
        if (!session) return null
        if (currentSessionId.value || agentId.value !== creatingForAgent) return null
        resetSession(session)
      } finally { isSubmitting.value = false }
    }
    return currentSessionId.value
  },
  submitTask: async (sid, requestId, text) => {
    if (sid !== currentSessionId.value || !allowRunningMessages.value) throw new Error('会话已切换，请重新说一遍')
    await enqueueMessage(sid, requestId, text, { memoryActive: memoryActive.value, planActive: planActive.value,
      toolProcessActive: toolProcessActive.value, fileIds: [], planMode: planMode.value })
    void conversationQueue.refresh()
    return queueSnapshot.value?.paused ? '已排队，任务暂停中，请在界面继续。' : '已接收，开始处理。'
  },
  submitInstruction: async (sid, requestId, text, runId) => {
    if (sid !== currentSessionId.value || !allowRunningMessages.value) throw new Error('会话已切换，请重新说一遍')
    await submitInstruction(sid, runId, requestId, text)
    return '补充要求已接收，会在任务下一次推理时应用。'
  },
})

// 输入框内容
const inputText = ref('')
// 创建会话、保存消息期间尚未进入运行态，也必须同步阻止重复提交。
const isSubmitting = ref(false)
const isSending = computed(() => isSubmitting.value || queueSending.value)

/**
 * 欢迎语与预设问题：统一来自智能体的「功能增强 · Web App 用户体验」配置
 * （与智能体编排页右栏预览同一份数据，保证「预览是什么样，真实对话就是什么样」）
 */
const welcomeHeadline = computed(() =>
  agentDetail.value?.uiConfig?.openingMessage?.trim()
    || `来和 ${agentDetail.value?.name || '智能体'} 聊聊吧`
)

/** 预设问题（最多 6 条） */
const openingQuestions = computed(() =>
  (agentDetail.value?.uiConfig?.openingQuestions || [])
    .map(item => (item || '').trim())
    .filter(Boolean)
    .slice(0, 6)
)

/**
 * 点预设问题：填入输入框并直接发送
 * @param question 预设问题文案
 */
async function handlePickQuestion(question: string) {
  if (isSending.value || isRunning.value || isStopping.value) return
  inputText.value = question
  await handleSend()
}

function subAgentInvocationId(message: ChatMessageVO): string | undefined {
  if (message.subAgentRun?.invocationId) return message.subAgentRun.invocationId
  if (typeof message.id === 'string' && message.id.startsWith('subagent:')) {
    return message.id.slice('subagent:'.length)
  }
  try {
    return JSON.parse(message.content || '{}').invocationId as string | undefined
  } catch {
    return undefined
  }
}

// Insert the real-time anchor into the existing conversation timeline at STARTED time. The
// summary generated by the main agent subsequently appends after it, instead of pushing the
// completed child card to the bottom of the screen.
watch(subAgentRuns, (runs) => {
  const sessionId = currentSessionId.value
  if (!sessionId) return
  for (const run of runs) {
    const existing = messagesList.value.find((message) =>
      message.role === 'subagent' && subAgentInvocationId(message) === run.invocationId)
    if (existing) {
      existing.subAgentRun = run
      continue
    }
    messagesList.value.push({
      id: `subagent:${run.invocationId}`,
      sessionId,
      role: 'subagent',
      content: JSON.stringify({ invocationId: run.invocationId }),
      parentId: '',
      path: '',
      depth: 0,
      createdAt: '',
      subAgentRun: run,
    })
  }
}, { deep: true })

/** 构建文件前缀字符串 */
function buildFilesPrefix(files: UploadedFileItem[]): string {
  if (!files.length) return ''
  return JSON.stringify({ files }) + '@==##::::##==@'
}

// 构建展示消息
const displayMessages = computed<DisplayMessage[]>(() => {
  const list: DisplayMessage[] = []
  const branchLookup = branchMap.value
  // 最近一条用户消息：助手回复的直接父级多为 thinking/tool 消息，
  // 「重新生成」需要回溯到该提问（与后端 switchCurrentMessage 的用法一致）
  let lastUserMessage: ChatMessageVO | undefined
  for (let i = 0; i < messagesList.value.length; i++) {
    const m = messagesList.value[i]
    if (!m || m.role === 'system' || !m.content) continue
    if (m.role === 'user') {
      lastUserMessage = m
    }

    // 气泡级「重新生成」：该回复之前存在提问即可重发（运行中由 branchDisabled 禁用）
    const canRegenerate = m.role === 'assistant' && !!lastUserMessage
    const branch = branchLookup.get(String(m.id))

    list.push({
      id: String(m.id),
      role: m.role as DisplayMessage['role'],
      content: m.content || '',
      createdAt: m.createdAt,
      isStreaming: false,
      subAgentRun: m.subAgentRun ?? null,
      canRegenerate,
      branchIndex: branch ? branch.currentIndex : null,
      branchTotal: branch ? branch.total : 0,
    })
  }

  if (streamingMessageId.value) {
    list.push({
      id: streamingMessageId.value,
      role: streamingRole.value,
      content: streamingContent.value,
      isStreaming: true,
    })
  }  else {
    // 响应加载动画（没有任何推理或文本内容时）
    if (list[list.length - 1]?.role === 'user') {
      list.push({
        id: '',
        role: 'assistant',
        content: '',
        isStreaming: true,
      })
    }
  }

  if (memoryCompressionActive.value) {
    list.push({
      id: 'memory-compression',
      role: 'thinking',
      content: '',
      isStreaming: true,
      isMemoryCompression: true,
    })
  }

  return list
})

// 重命名模态框
const renameModalVisible = ref(false)
const renameSessionRef = ref<any>(null)
const renameTitle = ref('')
const renameSubmitting = ref(false)

// 新会话
const handleNewSession = async () => {
  // 断开当前 SSE 连接，旧会话继续后台运行
  preserveRunningSession.value = true
  disconnectStream()

  // 开启工作空间的情况特殊处理
  if (hasCodeExecutionConfig.value) {
    if (currentSessionTitle.value === '新对话') {
      preserveRunningSession.value = false
      return
    }

    const existNewSession = otherSessions.value.find((s) => s.title === '新对话')
    if (existNewSession) {
      await selectSession({
        id: existNewSession.id,
        title: existNewSession.title || '新对话',
      } as ChatSessionVO)

      preserveRunningSession.value = false
      return
    }

    const newSession = await createSession(formatSessionTitle(null), true)
    if (!newSession) {
      preserveRunningSession.value = false
      return
    }
    resetSession({
      id: String(newSession.id),
      title:'新对话'
    } as ChatSessionVO)
  } else {
    resetSession(null)
  }

  preserveRunningSession.value = false
}

/**
 * HITL 刷新恢复：非运行中的会话可能处于「工具确认暂停态」（刷新/重进后前端内存态已丢），
 * 调 /agui/pending 从后端持久暂停态重建「允许/禁止」确认 UI，使其可续点并正常 resume。
 * 暂停态会话已不在 active-runs（RunTracker 已 markCompleted），故独立于 reconnect 单独恢复。
 * @param sid 会话 ID
 */
const restoreConfirm = async (sid: string) => {
  try {
    const pending = await getPending(sid)
    if (currentSessionId.value === sid && pending.length) restorePending(pending)
  } catch {
    // 忽略：无暂停态或网络错误
  }
}

// 选择会话
const handleSelectSession = async (session: ChatSessionVO) => {
  // 切换前断开当前 SSE，但不中断后台 Agent
  preserveRunningSession.value = true
  disconnectStream()
  resetStreamingState()
  await selectSession(session)
  // 如果目标会话在运行中，触发重连
  if (runningSessions.value.has(String(session.id))) {
    // 注意：不要加 await，否则会阻塞会话切换
    reconnectStream(String(session.id), true)
  } else {
    // 非运行中：尝试恢复 HITL 确认暂停态（不加 await，避免阻塞会话切换）
    void restoreConfirm(String(session.id))
  }
  preserveRunningSession.value = false
}

// 会话菜单操作
const handleSessionMenu = async (key: string, session: ChatSessionVO) => {
  const id = String(session.id)
  if (key === 'rename') {
    renameSessionRef.value = session
    renameTitle.value = session.title || '新对话'
    renameModalVisible.value = true
    return
  }
  if (key === 'pin') {
    await pinSession(id)
    if (currentSessionId.value === id) {
      // 若当前会话被置顶，可能需要更新列表，已自动重新加载
    }
    return
  }
  if (key === 'unpin') {
    await unpinSession(id)
    return
  }
  if (key === 'delete') {
    if (isRunning.value || isStopping.value) {
      message.info('请等待当前对话完成')
      return
    }
    Modal.confirm({
      title: '确认删除',
      content: '删除后无法恢复，是否继续？',
      onOk: async () => {
        await deleteSession(id)
        if (currentSessionId.value === id) {
          resetSession(null)
        }
      },
    })
    return
  }
}

/**
 * 一键清空对话记录：停止该智能体下所有运行中的会话，再批量删除会话与消息
 *
 * 删除范围只包含当前登录用户在该智能体下的会话（后端按 agentId + 当前用户过滤）；
 * 运行中的会话必须先在运行时打断，否则 Agent 会在会话被删后继续往库里写。
 */
const clearingSessions = ref(false)

const handleClearSessions = async () => {
  if (clearingSessions.value) return
  const aid = agentId.value
  if (!aid) {
    message.warning('智能体信息未就绪')
    return
  }
  clearingSessions.value = true
  try {
    // 1) 本智能体的会话 id（用于把「全实例活跃会话」收敛到本智能体，避免误停其他智能体正在跑的会话）
    let agentSessionIds = new Set<string>()
    let total = pinnedSessions.value.length + otherSessions.value.length
    try {
      const res = await chatSessionApi.listSessions({ agentId: aid })
      const rows = res.data?.data ?? []
      agentSessionIds = new Set(rows.map((s) => String(s.id)))
      total = rows.length
    } catch {
      // 列表拿不到就退回本地已加载的会话
      for (const s of [...pinnedSessions.value, ...otherSessions.value]) {
        agentSessionIds.add(String(s.id))
      }
    }

    if (total === 0) {
      message.info('当前没有对话记录')
      return
    }

    // 2) 运行态以 runtime 为准，但只保留本智能体的会话
    const localRunning = Array.from(runningSessions.value)
    let runningIds = localRunning.filter((tid) => agentSessionIds.has(tid))
    try {
      const activeIds = (await getActiveRuns()) ?? []
      runningIds = Array.from(new Set([
        ...runningIds,
        ...activeIds.filter((tid) => agentSessionIds.has(tid)),
      ]))
    } catch {
      // 运行态查不到就只用前端已知的运行中会话，避免误停其他智能体的轮次
    }

    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: '清空对话记录',
        content: `将删除当前智能体的 ${total} 条对话记录（含消息与工作区文件），删除后不可恢复。`
          + (runningIds.length > 0 ? `其中 ${runningIds.length} 条正在运行，会先自动停止。` : ''),
        okText: '清空',
        okType: 'danger',
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!confirmed) return

    // 3) 先停止运行中的会话，再删除
    for (const tid of runningIds) {
      try {
        await stopRun(tid)
      } catch {
        // 单个停止失败不阻断清空，后端删除后该轮次写库会因会话不存在而失败
      }
    }

    // 4) 断开本地 SSE 并清空流式状态，避免旧会话的事件继续渲染
    disconnectStream()
    resetStreamingState()
    runningSessions.value = new Set()

    // 5) 批量删除
    const deleted = await clearAgentSessions()
    resetSession(null)
    message.success(`已清空 ${deleted} 条对话记录`)
  } catch (err) {
    console.error('[Chat] 清空对话记录失败', err)
    message.error('清空对话记录失败，请稍后重试')
  } finally {
    clearingSessions.value = false
  }
}

// 提交重命名
const submitRename = async () => {
  const session = renameSessionRef.value
  if (!session) return
  const title = renameTitle.value.trim() || '新对话'
  renameSubmitting.value = true
  try {
    await updateSessionTitle(session.id, title)
    renameModalVisible.value = false
  } finally {
    renameSubmitting.value = false
  }
}

// HITL：value = { toolUseId, name, approved }，记录该工具决策（全部决策完内部自动调 resume 续跑）
const handelToolContent = (value: any) => {
  decideConfirm(value.toolUseId, value.approved)
}

// 处理交互提交
const handleInteractionSubmit = async (payload: InteractionSubmitPayload) => {
  if (!currentSessionId.value || isRunning.value || isStopping.value) return

  const sid = currentSessionId.value
  const data = payload.data as Record<string, unknown>

  // 1. 找到对应的 assistant 消息，将 submittedData 注入 UIP JSON（单次调用完成匹配+注入）
  let updatedContent: string | null = null
  const assistantMsg = [...messagesList.value].reverse().find(m => {
    if (m.role !== 'assistant' || !m.content) return false
    const updated = injectSubmissionToRawContent(m.content, payload.interactionId, data)
    if (updated !== m.content) {
      updatedContent = updated
      return true
    }
    return false
  })
  if (assistantMsg && updatedContent) {
    // 持久化到后端（后端通过 session 的 current_message_id 定位消息，无需传 messageId）
    chatSessionApi.updateCurrentMessageContent(sid, updatedContent)
      .catch(err => console.warn('[UIP] 持久化提交数据失败', err))
    // 同步更新本地消息列表，使渲染器立即读取 submittedData
    assistantMsg.content = updatedContent
  }

  // // 2. 保存用户提交消息到 DB（与 handleSend 保持一致，先 await 再 sendMessage）
  // try {
  //   const res = await chatSessionApi.appendMessage(sid, { role: 'user', content: userText })
  //   if (res.data?.data) messagesList.value.push(res.data.data as ChatMessageVO)
  // } catch (err) {
  //   console.warn('[UIP] 保存用户提交消息失败', err)
  // }

  // 3. 发送给 Agent 继续对话
  const userText = buildUserTextFromPayload(payload)
  await sendMessage(userText, [{ id: 'uip', role: 'user', content: userText }] as ChatMessageVO[])
}

// 处理 UIP 卡片渲染失败重试
const handleUIPRetry = async (uipCode: string) => {
  if (!currentSessionId.value || isRunning.value || isStopping.value) return

  // 构造重试消息：提示文本 + 原始 UIP 内容，让智能体参考修正
  const retryText = `上一条消息中的交互卡片生成有误，请重新生成。\n\n原始卡片内容：\n${uipCode}`

  await sendMessage(retryText, [{ id: 'uip', role: 'user', content: retryText }] as ChatMessageVO[])
}

// 处理 VEP 视觉卡片渲染失败重试
const handleVEPRetry = async (vepCode: string) => {
  if (!currentSessionId.value || isRunning.value || isStopping.value) return

  // 构造重试消息：提示文本 + 原始 VEP 内容，让智能体参考修正
  const retryText = `上一条消息中的视觉卡片生成有误，请重新生成。\n\n原始卡片内容：\n${vepCode}`

  await sendMessage(retryText, [{ id: 'vep', role: 'user', content: retryText }] as ChatMessageVO[])
}

/* ===================== 回复分支（重新生成 / 切换历史分支） ===================== */

/** 当前路径上的消息分支信息（同一父节点下的多个候选回复） */
const branches = ref<MessageBranchVO[]>([])

/**
 * 加载分支信息（后端仅返回兄弟数大于 1 的消息）
 */
const loadBranches = async () => {
  if (!currentSessionId.value) {
    branches.value = []
    return
  }
  try {
    const res = await chatSessionApi.getMessageBranches(currentSessionId.value)
    branches.value = (res.data?.data || []) as MessageBranchVO[]
  } catch (e) {
    console.warn('[分支] 加载分支信息失败', e)
    branches.value = []
  }
}

/** 会话切换后刷新分支信息 */
watch(currentSessionId, () => {
  void loadBranches()
})

/** 分支信息按消息 ID 建索引，供气泡内切换控件使用 */
const branchMap = computed(() => {
  const map = new Map<string, MessageBranchVO>()
  for (const item of branches.value) {
    map.set(String(item.messageId), item)
  }
  return map
})

/**
 * 切换历史分支：跳转到同一父节点下的相邻兄弟回复
 *
 * @param branch 分支信息
 * @param delta  偏移（-1 上一版，1 下一版）
 */
const switchBranch = async (branch: MessageBranchVO, delta: number) => {
  if (!currentSessionId.value || isRunning.value || isStopping.value) return
  const nextIndex = branch.currentIndex + delta
  const siblingId = branch.siblingIds?.[nextIndex]
  if (!siblingId) return
  try {
    await chatSessionApi.switchCurrentMessage(currentSessionId.value, String(siblingId))
    await loadCurrentMessages()
    await loadBranches()
  } catch (e) {
    console.error('[分支] 切换分支失败', e)
  }
}

/**
 * 气泡内切换回复分支
 *
 * @param payload.messageId 目标回复消息 ID
 * @param payload.delta     偏移（-1 上一版，1 下一版）
 */
const handleMessageSwitchBranch = async (payload: { messageId: string; delta: number }) => {
  const branch = branchMap.value.get(String(payload.messageId))
  if (!branch) return
  await switchBranch(branch, payload.delta)
}

/**
 * 重新生成指定的回复
 *
 * 先把会话游标移回该回复的父消息（用户消息），再重新发起一次运行；
 * 后端会把新的助手回复挂在该用户消息下（与旧回复成为兄弟分支）。
 *
 * @param messageId 目标回复消息 ID
 */
const handleMessageRegenerate = async (messageId: string) => {
  if (!currentSessionId.value || isRunning.value || isStopping.value) return
  const target = messagesList.value.find((m) => String(m.id) === String(messageId))
  if (!target) return

  let parent = target.parentId
    ? messagesList.value.find((m) => String(m.id) === String(target.parentId))
    : undefined
  // 父消息不是可重发的用户消息时，回退到该回复之前最近的一条用户消息
  if (!parent || parent.role !== 'user' || !parent.content) {
    const idx = messagesList.value.indexOf(target)
    parent = messagesList.value
      .slice(0, idx >= 0 ? idx : messagesList.value.length)
      .reverse()
      .find((m) => m.role === 'user' && m.content)
  }
  if (!parent || !parent.content) {
    message.warning('没有可重新生成的用户消息')
    return
  }

  const text = parent.content
  try {
    // 游标移回用户消息：新回复将成为旧回复的兄弟分支
    await chatSessionApi.switchCurrentMessage(currentSessionId.value, String(parent.id))
    await sendMessage(text, [{ id: 'regen', role: 'user', content: text }] as ChatMessageVO[])
    await loadCurrentMessages()
    await loadBranches()
  } catch (e) {
    console.error('[分支] 重新生成失败', e)
  }
}

// 发送消息
const handleSend = async () => {
  const text = inputText.value.trim()
  const filesToSend = uploadedFiles.value.filter((f) => !f.uploading)
  const hasFiles = filesToSend.length > 0
  if ((!text && !hasFiles) || !agentId.value || isStopping.value || isSending.value) return

  const finalText = hasFiles ? buildFilesPrefix(filesToSend) + text : text
  const fileIdsToSend = filesToSend.map((f) => f.id)

  let runPromise: Promise<void> | undefined
  isSubmitting.value = true
  try {
    if (currentSessionId.value && allowRunningMessages.value && (isRunning.value
        || queueSnapshot.value?.messages.some(m => m.state === 'QUEUED')
        || ['RUNNING', 'WAITING', 'STOPPING', 'INTERRUPTED'].includes(queueSnapshot.value?.state ?? '') || queueSnapshot.value?.paused)) {
      try {
        const accepted = await conversationQueue.enqueue(finalText, { fileIds: fileIdsToSend,
          memoryActive: memoryActive.value, planActive: planActive.value, toolProcessActive: toolProcessActive.value, planMode: planMode.value })
        if (!accepted) return
        if (inputText.value.trim() === text) inputText.value = ''
        uploadedFiles.value = uploadedFiles.value.filter(file => !fileIdsToSend.includes(file.id))
      } catch (e) { message.error(e instanceof Error ? e.message : '消息发送失败，请重试') }
      return
    }
    if (isRunning.value) return

    // 立即清空输入框和附件，提升交互体验
    inputText.value = ''
    uploadedFiles.value = []

    // 新建会话已知为空，初始化本地状态，不与首条消息保存并发回填历史。
    if (!currentSessionId.value) {
      const titleInput = text || '新对话'
      const newSession = await createSession(formatSessionTitle(titleInput))
      if (!newSession) return
      resetSession(newSession)
    }

    // 保存用户消息
    const sessionId = currentSessionId.value
    if (!sessionId) return
    const userMsg = await chatSessionApi.appendMessage(sessionId, { role: 'user', content: finalText })
    if (currentSessionId.value !== sessionId) return
    // 如果是新会话，更新标题
    if (messagesList.value.length <= 1) {
      const title = formatSessionTitle(text || '新对话')
      await updateSessionTitle(sessionId, title)
      if (currentSessionId.value !== sessionId) return
      currentSessionTitle.value = title
    }
    const savedMessage = userMsg.data.data
    const existing = messagesList.value.find(item => String(item.id) === String(savedMessage.id))
    if (existing) Object.assign(existing, savedMessage)
    else messagesList.value.push(savedMessage)

    // 发起运行会同步设置 isRunning；随后释放提交锁，允许运行中排队。
    runPromise = sendMessage(
      finalText,
      [{ role: 'user', content: finalText }] as ChatMessageVO[],
      fileIdsToSend
    )
  } finally {
    isSubmitting.value = false
  }

  await runPromise
}

// 切换侧边栏（通过 computed setter 自动持久化到 store）
const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

/** 工作空间面板开关状态 */
const workspacePanelOpen = ref(false)

/** 工作空间面板引用（供外部调用 startFileOperation 等） */
const workspacePanelRef = ref<InstanceType<typeof WorkspacePanel> | null>(null)

/**
 * 切换工作空间面板显示/隐藏
 */
const toggleWorkspace = () => {
  workspacePanelOpen.value = !workspacePanelOpen.value
}

/** 工作空间右栏宽度（按 agent + 账号维度持久化） */
const workspacePanelWidth = computed(() => {
  const id = agentDetail.value?.id ?? agentId.value
  // getWorkspacePanelWidth 内部读取 preferences，响应性依赖由此自动建立
  return chatStore.getWorkspacePanelWidth(id as string, accountId.value as string)
})

/**
 * 右栏拖拽结束：持久化宽度
 *
 * 只在松手时写一次，拖拽过程由 WorkspacePanel 本地维护宽度。
 *
 * @param width 最终宽度（像素）
 */
const handleWorkspaceResize = (width: number) => {
  const id = agentDetail.value?.id ?? agentId.value
  chatStore.setWorkspacePanelWidth(id as string, accountId.value as string, width)
}

/**
 * 对话内点击工作空间文件 → 在右栏打开预览
 *
 * 由 ChatMain 抛上来（仅当传了 delegate-file-preview）。
 * 编排页预览不传该属性，因此走的是 ChatMain 内置的 Modal 兜底。
 *
 * @param file 工作空间文件
 */
const handleOpenWorkspaceFile = (file: FlatFileItem) => {
  workspacePanelOpen.value = true
  // 面板可能是刚由 v-if 挂载出来的，需等一帧再拿它的 ref
  void nextTick(() => {
    workspacePanelRef.value?.openPreview(file)
  })
}

// ==================== 对话产物 ====================

const { artifacts } = useChatArtifacts(currentSessionId, computed(() => isRunning.value || isStopping.value))

/**
 * 点击产物卡片 → 在右栏打开预览
 *
 * 复用工作空间预览链路：产物本身就是工作空间里的文件。
 *
 * @param artifact 产物
 */
const handleOpenArtifact = (artifact: ChatArtifactVO) => {
  handleOpenWorkspaceFile({
    path: artifact.filePath,
    name: artifact.fileName,
    fullName: artifact.fileName,
    folderPath: artifact.filePath.includes('/')
      ? artifact.filePath.slice(0, artifact.filePath.lastIndexOf('/'))
      : '',
    extension: artifact.extension ?? undefined
  })
}

/** 挂好产物的展示消息列表（传给 MessageList 渲染） */
const displayMessagesWithArtifacts = computed(() =>
  attachArtifacts(displayMessages.value, artifacts.value)
)

// 跟踪所有后台运行中的会话 ID
const runningSessions = ref<Set<string>>(new Set())

/** 临时标志：会话切换/新建期间，阻止 watch(isRunning) 误删旧 session 的运行态 */
const preserveRunningSession = ref(false)

// 后台会话状态轮询（5s 间隔）
let pollingTimer: ReturnType<typeof setInterval> | null = null

const startPolling = () => {
  if (pollingTimer) return
  pollingTimer = setInterval(async () => {
    if (runningSessions.value.size === 0) {
      stopPolling()
      return
    }
    for (const tid of runningSessions.value) {
      if (tid === currentSessionId.value) continue
      try {
        const running = await getStatus(tid)
        if (!running) {
          const next = new Set(runningSessions.value)
          next.delete(tid)
          runningSessions.value = next
        }
      } catch {
        // 忽略网络错误
      }
    }
  }, 5000)
}

const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

// 初始化：获取活跃运行列表，若当前会话在运行则重连
onMounted(async () => {
  loadSessions()
  try {
    const activeIds = await getActiveRuns()
    runningSessions.value = new Set(activeIds)
    if (currentSessionId.value) {
      if (activeIds.includes(currentSessionId.value)) {
        reconnectStream(currentSessionId.value)
      } else {
        // 非运行中：可能是 HITL 确认暂停态，尝试从持久暂停态重建确认 UI
        void restoreConfirm(currentSessionId.value)
      }
    }
    if (activeIds.length > 0) {
      startPolling()
    }
  } catch {
    // 忽略初始化错误
  }
})

// 组件卸载时清理 poll timer
onBeforeUnmount(() => {
  stopPolling()
  disconnectStream()
})

// 运行状态变化时更新 runningSessions，停止确认期间仍保持会话忙碌标记。
watch([isRunning, isStopping], ([running, stopping]) => {
  const sid = currentSessionId.value
  if (!sid) return
  const next = new Set(runningSessions.value)
  if (running || stopping) {
    next.add(sid)
    startPolling()
  } else {
    if (!preserveRunningSession.value) {
      next.delete(sid)
    }
  }
  runningSessions.value = next
})
</script>

<template>
  <div class="chat-page">
    <ChatSidebar
      :collapsed="sidebarCollapsed"
      :agent-name="agentDetail?.name"
      :pinned-sessions="pinnedSessions"
      :other-sessions="otherSessions"
      :current-session-id="currentSessionId"
      :running-sessions="runningSessions"
      :user-nickname="userInfo?.nickname"
      :loading="sessionsLoading"
      :has-more="sessionsHasMore"
      :show-account="showAccount"
      @toggle-collapse="toggleSidebar"
      @new-session="handleNewSession"
      @clear-sessions="handleClearSessions"
      @select-session="handleSelectSession"
      @session-menu="handleSessionMenu"
      @load-more="loadMoreSessions"
    />

    <RenameModal
      v-model:visible="renameModalVisible"
      v-model:title="renameTitle"
      :confirm-loading="renameSubmitting"
      @ok="submitRename"
    />

    <div v-if="loadingMessages" class="loading-messages"><LoadingOutlined style="margin-right: 6px" />加载中</div>
    <ChatMain
      :allow-instructions="!shareIdentity"
      :allow-usage="!shareIdentity && !!agentDetail"
      :show-model-usage="agentDetail?.uiConfig?.showModelUsage"
      :queue-snapshot="queueSnapshot"
      :queue-error="queueError"
      :is-sending="isSending"
      @queue-changed="conversationQueue.refresh()"
      v-else
      ref="chatMainRef"
      :title="currentSessionTitle || agentDetail?.name || '对话'"
      :message-size="messagesList.length"
      :welcome-headline="welcomeHeadline"
      :welcome-desc="agentDetail?.description || '有什么想说的，直接发给我就好～'"
      :messages="displayMessagesWithArtifacts"
      :tool-calls="toolCallsInProgress"
      :input-value="inputText"
      :uploaded-files="uploadedFiles"
      :isRunning="isRunning"
      :is-stopping="isStopping"
      :agent-id="agentId"
      :allow-plan-mode="allowPlanMode"
      :can-view-plan="canViewPlan"
      :plan-mode="planMode"
      @plan-mode="planMode = $event"
      :allow-upload-file-type="allowFileType"
      :agent-has-result="agentHasResult"
      :show-thinking="agentDetail?.reasoningConfig?.showThinking !== false"
      :workspace-panel-open="workspacePanelOpen"
      :has-code-execution-config="!!hasCodeExecutionConfig"
      :session-id="currentSessionId"
      :session-message-table="currentSessionMessageTable"
      :has-more-history="hasMoreHistory"
      :history-loading="historyLoading"
      :current-plan="currentPlan"
      :context-usage="contextUsage"
      :memory-compression-active="memoryCompressionActive"
      :opening-questions="openingQuestions"
      delegate-file-preview
      @update:input-value="inputText = $event"
      @update:uploaded-files="uploadedFiles = $event"
      @pick-question="handlePickQuestion"
      @toolContent="handelToolContent"
      @send="handleSend"
      @abort="abortRun"
      @toggle-sidebar="toggleSidebar"
      @toggle-workspace="toggleWorkspace"
      @open-workspace-file="handleOpenWorkspaceFile"
      @open-artifact="handleOpenArtifact"
      @load-more-history="loadMoreHistory"
      @new-session="handleNewSession"
      @plan-destroyed="currentPlan = null"
      @interaction-submit="handleInteractionSubmit"
      @uip-retry="handleUIPRetry"
      @vep-retry="handleVEPRetry"
      @switch-branch="handleMessageSwitchBranch"
      @regenerate="handleMessageRegenerate"
    >
      <template #voice>
        <VoiceConversation v-if="allowVoiceConversation" v-permission="['agent:chat:instruct']"
          :available="voice.available.value" :active="voice.active.value" :connecting="voice.connecting.value"
          :muted="voice.muted.value" :state="voice.state.value" :error="voice.error.value"
          :user-caption="voice.userCaption.value" :assistant-caption="voice.assistantCaption.value"
          :needs-audio-start="voice.needsAudioStart.value" @start="voice.start" @disconnect="voice.disconnect"
          @mute="voice.toggleMute" @interrupt="voice.interrupt" @start-audio="voice.startAudio" />
      </template>
    </ChatMain>
    <!-- 工作空间面板（作为 flex 子项从右侧滑出；兼作文件预览与文章编写宿主）
         不再以「是否配了代码执行」为条件：发消息会无条件创建会话（见 handleSend），
         这类会话同样有工作空间目录，用户可以上传文件、预览、进文章编写模式。
         智能体能否写文件由它自己的代码执行配置决定，与面板是否可见无关。 -->
    <WorkspacePanel
      v-if="currentSessionId && !loadingMessages"
      ref="workspacePanelRef"
      :session-id="currentSessionId"
      :width="workspacePanelWidth"
      :class="{ open: workspacePanelOpen }"
      @close="workspacePanelOpen = false"
      @resize="handleWorkspaceResize"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;
.loading-messages {
  width: 100%;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
}
</style>
