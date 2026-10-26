<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 右侧「预览与调试」面板
 *
 * 直接复用**原生对话界面** `ChatMain`（与「对话」页同一套组件/样式/交互），
 * 只把数据源换成「不落库的调试会话」：
 * - 用 `useChatStream` 走既有的 AG-UI 客户端，`threadId` 用前端临时生成的值
 *   （服务端不建 `chat_session`，因此不会出现在「对话」列表里）；
 * - 消息只留在内存里（`onMessageSaved` 收集完成态 + 流式态拼接），
 *   与「对话」页的 `displayMessages` 同口径，从而复用同一套气泡/推理/工具卡片渲染。
 *
 * @component
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ArrowRightOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons-vue'
import ChatMain from '@/components/chat/ChatMain.vue'
import { useChatStream } from '@/composables/chat/useChatStream'
import type { ChatMessageVO, DisplayMessage, UploadedFileItem } from '@/types'

const props = defineProps<{
  /** 智能体主键 ID（AG-UI forwardedProps.agentId） */
  agentId: string
  /** 智能体编号（AG-UI run 路径参数） */
  agentCode: string
  /** 智能体名称（面板标题 / 欢迎语兜底） */
  agentName: string
  /** 智能体描述（欢迎语副文案） */
  agentDescription?: string
  /** 是否开启长期记忆 */
  memoryActive?: boolean
  /** 是否展示工具调用过程 */
  showToolProcess?: boolean
  showThinking?: boolean
  /** 是否启用任务清单 */
  enablePlanning?: boolean
  /** 是否已启用 */
  enabled?: boolean
  /** 是否存在未保存的改动（未保存时跑的是服务端旧配置） */
  dirty?: boolean
  /** 开场白（功能增强 · Web App 用户体验） */
  openingMessage?: string
  /** 推荐问题 */
  openingQuestions?: string[]
}>()

const emit = defineEmits<{
  /** 打开「功能增强 · Web App 用户体验」弹窗 */
  openFeatures: []
}>()

/** 调试会话的临时 threadId（首条消息发送时生成，清空面板后轮换） */
const debugThreadId = ref<string | null>(null)

/** 内存态消息列表（完成后的消息，不落库） */
const debugMessages = ref<ChatMessageVO[]>([])

const inputText = ref('')
const uploadedFiles = ref<UploadedFileItem[]>([])

/** 调试面板不做附件上传（不绑定具体会话工作空间） */
const fileIds = computed<string[]>(() => [])

/** 与正式对话一致，运行选项直接使用智能体设置。 */
const memoryActiveRef = computed(() => props.memoryActive === true)
const planActiveRef = computed(() => props.enablePlanning === true)
const toolProcessRef = computed(() => props.showToolProcess === true)

const {
  agentHasResult,
  streamingContent,
  streamingMessageId,
  streamingRole,
  toolCallsInProgress,
  isRunning,
  isStopping,
  currentPlan,
  contextUsage,
  memoryCompressionActive,
  sendMessage,
  abortRun,
  disconnect,
  resetStreamingState
} = useChatStream(
  computed(() => props.agentId),
  computed(() => ({ agentCode: props.agentCode, allowFileType: [] })),
  debugThreadId,
  fileIds,
  memoryActiveRef,
  planActiveRef,
  toolProcessRef,
  (chatMsg: ChatMessageVO) => {
    // useChatStream 在「有 sessionId」时才回调，这里用临时 threadId 充当 sessionId
    if (String(chatMsg.sessionId) !== debugThreadId.value) return
    const existing = debugMessages.value.find(item => String(item.id) === String(chatMsg.id))
    if (existing) Object.assign(existing, chatMsg)
    else debugMessages.value.push(chatMsg)
  }
)

/** 生成并记住本次调试会话的 threadId */
function ensureThreadId(): string {
  if (!debugThreadId.value) {
    debugThreadId.value = `thread_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  }
  return debugThreadId.value
}

/** 与「对话」页同口径：把完成态消息 + 流式态拼成展示列表 */
const displayMessages = computed<DisplayMessage[]>(() => {
  const list: DisplayMessage[] = []
  for (const m of debugMessages.value) {
    if (!m || m.role === 'system' || !m.content) continue
    list.push({
      id: String(m.id),
      role: m.role as DisplayMessage['role'],
      content: m.content || '',
      createdAt: m.createdAt,
      isStreaming: false,
      subAgentRun: m.subAgentRun ?? null
    })
  }

  if (streamingMessageId.value) {
    list.push({
      id: streamingMessageId.value,
      role: streamingRole.value,
      content: streamingContent.value,
      isStreaming: true
    })
  } else if (list[list.length - 1]?.role === 'user') {
    // 响应加载动画（还没有任何推理或文本时）
    list.push({ id: '', role: 'assistant', content: '', isStreaming: true })
  }

  if (memoryCompressionActive.value) {
    list.push({
      id: 'memory-compression',
      role: 'thinking',
      content: '',
      isStreaming: true,
      isMemoryCompression: true
    })
  }

  return list
})

/** ChatMain 的 messageSize 决定「欢迎态 / 消息态」 */
const messageSize = computed(() => displayMessages.value.length)

/** 欢迎语：优先智能体配置的开场白，其次「你好，我是你的智能助手」 */
const welcomeHeadline = computed(() => props.openingMessage?.trim() || '你好，我是你的智能助手')

/** 欢迎语副文案：智能体描述 */
const welcomeDesc = computed(() => props.agentDescription || props.agentName)

/** 欢迎介绍区的推荐问题，无配置时不展示。 */
const starterQuestions = computed(() =>
  (props.openingQuestions || [])
    .map(item => (item || '').trim())
    .filter(Boolean)
    .slice(0, 6)
)

/**
 * 发送：走原生对话的 sendMessage（内部即 AG-UI run）
 */
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isRunning.value) return
  if (!props.agentCode) {
    inputText.value = text
    return
  }
  if (props.dirty) {
    // 试跑读的是服务端已保存配置，未保存时先提示（不阻塞）
    message.warning('当前有未保存的更改，本次调试跑的仍是「已保存版本」')
  }
  inputText.value = ''
  ensureThreadId()

  const userMsg: ChatMessageVO = {
    id: `user_${Date.now()}`,
    sessionId: debugThreadId.value as string,
    role: 'user',
    content: text,
    parentId: '',
    path: '',
    depth: 0,
    createdAt: new Date().toISOString()
  } as ChatMessageVO
  debugMessages.value.push(userMsg)

  await sendMessage(text, [userMsg])
}

/** 点推荐问题即发送 */
async function askStarter(question: string) {
  inputText.value = question
  await handleSend()
}

/** 清空面板（仅清内存态，服务端本就没有落库会话） */
function reset() {
  abortRun()
  disconnect()
  resetStreamingState()
  debugMessages.value = []
  inputText.value = ''
  uploadedFiles.value = []
  if (debugThreadId.value) {
    // 换一个 threadId，避免续用上一次的 AgentScope 会话记忆
    debugThreadId.value = null
  }
}

/** 智能体切换时重置 */
watch(() => props.agentId, () => reset())

onBeforeUnmount(() => {
  disconnect()
})
</script>

<template>
  <aside class="agstudio-preview">
    <header class="agstudio-preview__header">
      <div class="agstudio-preview__title">
        <strong>预览与调试</strong>
        <span class="agstudio-preview__model">{{ agentCode || '未设置编号' }}</span>
        <span v-if="dirty" class="agstudio-preview__flag is-warn">含未保存改动</span>
        <span v-else-if="!enabled" class="agstudio-preview__flag is-warn">未启用</span>
      </div>
      <div class="agstudio-preview__header-actions">
        <a-button v-if="isRunning" size="small" danger @click="abortRun">
          <template #icon><StopOutlined /></template>
          停止
        </a-button>
        <a-tooltip title="清空面板">
          <a-button size="small" type="text" @click="reset">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
    </header>

    <!-- 原生对话界面（同「对话」页组件） -->
    <div class="agstudio-preview__chat">
      <ChatMain
        :title="agentName"
        :message-size="messageSize"
        :welcome-headline="welcomeHeadline"
        :welcome-desc="welcomeDesc"
        :messages="displayMessages"
        :tool-calls="toolCallsInProgress"
        :input-value="inputText"
        :uploaded-files="uploadedFiles"
        :is-running="isRunning"
        :is-stopping="isStopping"
        :agent-id="agentId"
        :allow-upload-file-type="[]"
        :agent-has-result="agentHasResult"
        :show-thinking="showThinking"
        :session-id="debugThreadId"
        :session-message-table="null"
        :has-more-history="false"
        :history-loading="false"
        :current-plan="currentPlan"
        :context-usage="contextUsage"
        :memory-compression-active="memoryCompressionActive"
        :opening-questions="starterQuestions"
        @update:input-value="inputText = $event"
        @update:uploaded-files="uploadedFiles = $event"
        @send="handleSend"
        @abort="abortRun"
        @pick-question="askStarter"
      />
    </div>

    <footer class="agstudio-preview__banner" role="button" tabindex="0" @click="emit('openFeatures')" @keydown.enter="emit('openFeatures')">
      <span>开启功能增强 · Web App 用户体验</span>
      <ArrowRightOutlined />
    </footer>
  </aside>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-preview.scss' as *;
</style>
