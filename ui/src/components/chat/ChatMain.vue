<script setup lang="ts">
import {computed, ref, watch, onMounted, onUnmounted} from 'vue'
import {
  MenuOutlined,
  FolderOutlined,
  FolderOpenOutlined,
  LoadingOutlined,
  ArrowDownOutlined
} from '@ant-design/icons-vue'
import MessageList from './MessageList.vue'
import MessageNavigator from './MessageNavigator.vue'
import ChatInput from './ChatInput.vue'
import AgentInstructions from './AgentInstructions.vue'
import AgentRunUsage from './AgentRunUsage.vue'
import ConversationQueue from './ConversationQueue.vue'
import AgentPlanDocument from './AgentPlanDocument.vue'
import type { QueueSnapshot } from '@/api/agui/queue'
import { useAccountStore } from '@/stores'
import Welcome from './Welcome.vue'
import PlanPanel from './PlanPanel.vue'
import type { DisplayMessage, UploadedFileItem, PlanInfo } from '@/types'
import type { ContextUsageEvent } from '@/types'
import type { FlatFileItem } from "@/composables/chat/useWorkspaceFiles.ts";
import type { ChatArtifactVO } from '@/types/vo'
import type { InteractionSubmitPayload } from '@/components/markdown/uip/types'
import WorkspaceFilePreview from "@/components/workspace/WorkspaceFilePreview.vue";

const accountStore = useAccountStore()
const props = withDefaults(defineProps<{
  title: string
  messageSize: number
  welcomeHeadline: string
  welcomeDesc?: string
  messages: DisplayMessage[]
  toolCalls: any[]
  inputValue: string
  uploadedFiles?: UploadedFileItem[]
  isRunning: boolean
  isStopping?: boolean
  isSending?: boolean
  agentId: string
  allowPlanMode?: boolean
  canViewPlan?: boolean
  planMode?: boolean
  allowUploadFileType?: string[]
  agentHasResult?: boolean
  showThinking?: boolean
  workspacePanelOpen?: boolean
  hasCodeExecutionConfig?: boolean
  sessionId?: string | null
  allowInstructions?: boolean
  /** 正式登录会话可查询实际用量，独立于补充要求和任务队列。 */
  allowUsage?: boolean
  /** 跟随智能体的用量展示配置，未配置时保持显示。 */
  showModelUsage?: boolean
  queueSnapshot?: QueueSnapshot | null
  queueError?: string
  sessionMessageTable?: string | null
  /** 是否还有更早的历史消息 */
  hasMoreHistory?: boolean
  /** 历史消息加载中 */
  historyLoading?: boolean
  /** 当前计划信息 */
  currentPlan?: PlanInfo | null
  contextUsage?: ContextUsageEvent['value'] | null
  memoryCompressionActive?: boolean
  /** 预设问题（开场白配置），对话页与编排页预览共用 */
  openingQuestions?: string[]
  /**
   * 是否把工作空间文件预览交给父级处理
   *
   * 对话页有右栏可作为预览宿主，故置 true —— 点击文件标签会在右栏打开预览。
   * 编排页预览（AgentStudioPreview）**没有右栏宿主**，不传此属性，
   * 于是回落到本组件内置的全屏 Modal，保证那条路径不受影响。
   */
  delegateFilePreview?: boolean
}>(), { showModelUsage: true })

const canViewUsage = computed(() => props.allowUsage && props.showModelUsage !== false
  && accountStore.hasPerm('agent:definition:list'))
const showWelcome = computed(() => props.messageSize <= 1 && !props.isRunning && !props.isSending
  && !props.messages.some(message => message.role !== 'system'))

const emit = defineEmits<{
  (e: 'update:inputValue', value: string): void
  (e: 'update:uploadedFiles', value: UploadedFileItem[]): void
  (e: 'send'): void
  (e: 'scroll', event: Event): void
  (e: 'toolContent', value: any): void
  (e: 'abort'): void
  (e: 'queueChanged'): void
  (e: 'planMode', value: boolean): void
  (e: 'toggleSidebar'): void
  (e: 'toggleWorkspace'): void
  /** 触发加载更多历史消息 */
  (e: 'loadMoreHistory'): void
  (e: 'newSession'): void
  /** 计划面板销毁 */
  (e: 'planDestroyed'): void
  (e: 'interactionSubmit', payload: InteractionSubmitPayload): void
  (e: 'uipRetry', uipCode: string): void
  (e: 'vepRetry', vepCode: string): void
  /** 切换回复分支 */
  (e: 'switchBranch', payload: { messageId: string; delta: number }): void
  /** 重新生成指定回复 */
  (e: 'regenerate', messageId: string): void
  /** 点了预设问题（父级负责填入并发送） */
  (e: 'pickQuestion', question: string): void
  /** 请求在右栏打开工作空间文件预览（仅在 delegateFilePreview 为 true 时抛出） */
  (e: 'openWorkspaceFile', file: FlatFileItem): void
  /** 点击产物卡片：请求在右栏打开预览 */
  (e: 'openArtifact', artifact: ChatArtifactVO): void
}>()

// 滚动容器 ref
const messagesScrollRef = ref<HTMLElement | null>(null)
const messagesContentRef = ref<HTMLElement | null>(null)
const shouldAutoScroll = ref(true)
const showReturnToLatest = ref(false)
const SCROLL_BOTTOM_THRESHOLD = 80
// 触顶加载阈值
const SCROLL_TOP_THRESHOLD = 50
// 加载前记录滚动位置
const savedScrollHeight = ref(0)
const savedScrollTop = ref(0)

const workspaceFilePreviewVisible = ref(false)
const workspaceFilePreviewNode = ref<FlatFileItem | null>(null)
// 标志位：区分程序化滚动与用户手动滚动，防止 scrollToBottom 触发的 scroll 事件错误更新 shouldAutoScroll
let programmaticTop: number | null = null
let historyRequested = false
let resizeObserver: ResizeObserver | undefined
// 待执行的自动滚动 rAF ID，用于在用户主动上滑时立即取消，打破"抗衡"
let scrollRafId: number | null = null

/**
 * 滚动到底部
 * 使用 requestAnimationFrame 确保在浏览器绘制前拿到最新的 scrollHeight。
 * 每次调度前取消上一次的待执行回调，执行时二次校验 shouldAutoScroll，
 * 防止用户上滑后被已调度的回调拉回底部。
 */
const scrollToBottom = () => {
  const el = messagesScrollRef.value
  if (!el) return

  // 取消上一次待执行的自动滚动，避免 rAF 堆积
  if (scrollRafId !== null) {
    cancelAnimationFrame(scrollRafId)
    scrollRafId = null
  }

  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = null
    if (!el) return

    // 二次校验：rAF 回调执行时用户可能已经上滑，此时应放弃自动滚底
    if (!shouldAutoScroll.value) return

    el.scrollTop = el.scrollHeight
    programmaticTop = el.scrollTop
    showReturnToLatest.value = false
  })
}

// 检查并更新自动滚动状态
const checkAndUpdateAutoScroll = () => {
  const el = messagesScrollRef.value
  if (!el) return

  const { scrollTop, scrollHeight, clientHeight } = el
  const distanceFromBottom = scrollHeight - scrollTop - clientHeight
  shouldAutoScroll.value = distanceFromBottom <= SCROLL_BOTTOM_THRESHOLD
  showReturnToLatest.value = !shouldAutoScroll.value
}

function pauseFollowing(event: WheelEvent) {
  if (event.deltaY >= 0) return
  shouldAutoScroll.value = false
  programmaticTop = null
  if (scrollRafId !== null) { cancelAnimationFrame(scrollRafId); scrollRafId = null }
}

function returnToLatest() {
  shouldAutoScroll.value = true
  showReturnToLatest.value = false
  scrollToBottom()
}

// 处理滚动事件
const handleScroll = (event: Event) => {
  // 跳过程序化滚动触发的事件，避免展开面板/DOM变化时错误更新 shouldAutoScroll
  if (programmaticTop !== null && Math.abs((messagesScrollRef.value?.scrollTop ?? 0) - programmaticTop) < 1) {
    programmaticTop = null
    emit('scroll', event)
    return
  }
  programmaticTop = null

  checkAndUpdateAutoScroll()

  // 用户主动上滑离开底部时，立即取消待执行的自动滚动，消除"抗衡感"
  if (!shouldAutoScroll.value && scrollRafId !== null) {
    cancelAnimationFrame(scrollRafId)
    scrollRafId = null
  }

  // 触顶检测：向上滚动接近顶部时加载更多历史消息
  const el = messagesScrollRef.value
  if (
    el && el.scrollTop <= SCROLL_TOP_THRESHOLD
    && props.hasMoreHistory
    && !props.historyLoading
    && !historyRequested
  ) {
    // 记录加载前的滚动位置
    savedScrollHeight.value = el.scrollHeight
    savedScrollTop.value = el.scrollTop
    historyRequested = true
    emit('loadMoreHistory')
  }

  emit('scroll', event)
}

// 处理发送事件
/**
 * 加载历史消息后保持滚动位置不变（视觉上不跳动）
 */
const maintainScrollPosition = () => {
  requestAnimationFrame(() => {
    const el = messagesScrollRef.value
    if (!el) return
    const heightDiff = el.scrollHeight - savedScrollHeight.value
    el.scrollTop = savedScrollTop.value + heightDiff
    programmaticTop = el.scrollTop
    historyRequested = false
    checkAndUpdateAutoScroll()
  })
}

const handleSend = () => {
  emit('send')
  // 发送后强制自动滚动到底部
  shouldAutoScroll.value = true
  scrollToBottom()
}

// 预览输入tag
const inputTagPreviewHandle = (file: FlatFileItem) => {
  // 父级有右栏宿主时交给父级，在右栏内预览
  if (props.delegateFilePreview) {
    emit('openWorkspaceFile', file)
    return
  }
  // 无宿主（编排页预览）回落到内置全屏 Modal
  workspaceFilePreviewVisible.value = true
  workspaceFilePreviewNode.value = file
}

// 监听消息变化，自动滚动
watch(
  () => props.messages,
  (newVal: DisplayMessage[], oldVal: DisplayMessage[]) => {
    // 历史消息加载：新消息被插入头部
    if (historyRequested && oldVal?.length && newVal.length > oldVal.length && newVal[0]?.id !== oldVal[0]?.id) {
      maintainScrollPosition()
      return
    }
    // 正常新消息：自动滚动到底部
    if (shouldAutoScroll.value) {
      scrollToBottom()
    }
  },
  { deep: true, flush: 'post' }
)

watch(() => props.historyLoading, loading => { if (!loading) historyRequested = false }, { flush: 'post' })
watch(() => props.sessionId, () => {
  historyRequested = false
  returnToLatest()
}, { flush: 'post' })

// 图片加载、代码块和引用卡片改变高度时，仅在跟随状态下保持底部可见。
watch(messagesContentRef, element => {
  resizeObserver?.disconnect()
  if (!element || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => {
    if (shouldAutoScroll.value && !historyRequested && !props.historyLoading) scrollToBottom()
  })
  resizeObserver.observe(element)
}, { flush: 'post' })

// 监听流式内容
watch(
  () => props.toolCalls,
  () => {
    if (shouldAutoScroll.value) {
      scrollToBottom()
    }
  },
  { deep: true, flush: 'post' }
)

// 组件挂载后滚动到底部
onMounted(() => {
  if (props.messages.length > 0) {
    scrollToBottom()
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  if (scrollRafId !== null) cancelAnimationFrame(scrollRafId)
})

// 暴露方法给父组件（如果需要）
defineExpose({
  scrollToBottom
})
</script>

<template>
  <main class="chat-main">
    <header class="chat-main-header">
      <!-- 移动端菜单按钮 -->
      <button
        type="button"
        class="chat-mobile-menu-btn"
        title="打开会话列表"
        @click="$emit('toggleSidebar')"
      >
        <MenuOutlined />
      </button>
      <h1 class="chat-main-title" :title="title">{{ title }}</h1>
      <!-- 工作空间入口按钮（与左侧菜单按钮对称）
           只要有会话就提供入口：工作空间的用途不止「看智能体写的文件」，
           也包含用户自己上传、预览与文章编写（见 views/Chat/index.vue 的面板挂载条件）。 -->
      <ATooltip placement="left" title="工作空间">
        <button
          v-if="sessionId"
          type="button"
          class="chat-workspace-btn"
          :class="{ 'is-active': workspacePanelOpen }"
          @click="$emit('toggleWorkspace')"
        >
          <FolderOpenOutlined v-if="workspacePanelOpen" />
          <FolderOutlined v-else />
        </button>
      </ATooltip>

    </header>

    <slot name="voice" />

    <div v-if="showWelcome" class="chat-welcome-container">
      <Welcome
        :message-size="messageSize"
        :headline="welcomeHeadline"
        :input-value="inputValue"
        :agent-id="agentId"
        :description="welcomeDesc"
        :uploaded-files="uploadedFiles"
        :isRunning="isRunning"
        :is-sending="isSending"
        :allow-plan-mode="allowPlanMode"
        :plan-mode="planMode"
        @plan-mode="emit('planMode', $event)"
        :allow-upload-file-type="allowUploadFileType"
        :session-id="sessionId"
        :has-code-execution-config="hasCodeExecutionConfig"
        :mention-allowed="true"
        :opening-questions="openingQuestions"
        @update:input-value="$emit('update:inputValue', $event)"
        @update:uploaded-files="$emit('update:uploadedFiles', $event)"
        @send="handleSend"
        @new-session="$emit('newSession')"
        @pick-question="$emit('pickQuestion', $event)"
      />
    </div>

    <template v-else>
      <div class="chat-messages-area">
        <div
          ref="messagesScrollRef"
          class="chat-main-messages-scroll"
          @scroll="handleScroll"
          @wheel.passive="pauseFollowing"
        >
          <div ref="messagesContentRef">
          <!-- 历史消息加载提示 -->
          <div v-if="hasMoreHistory || historyLoading" class="chat-history-loading">
            <template v-if="historyLoading">
              <LoadingOutlined style="margin-right: 6px; font-size: 14px" />
              <span>正在加载</span>
            </template>
            <template v-else-if="hasMoreHistory">
              <span>下拉加载更多历史消息</span>
            </template>
          </div>
          <PlanPanel
            v-if="currentPlan"
            :plan="currentPlan"
            :is-running="isRunning"
            @destroy="$emit('planDestroyed')"
          />
          <MessageList
            :show-thinking="showThinking"
            :session-id="sessionId"
            :agent-has-result="agentHasResult"
            :messages="messages"
            :tool-calls="toolCalls"
            :is-running="isRunning || !!isStopping"
            :branch-disabled="isRunning || !!isStopping"
            @inputTagPreview="inputTagPreviewHandle"
            @toolContent="(content: any) => $emit('toolContent', content)"
            @interaction-submit="$emit('interactionSubmit', $event)"
            @uip-retry="$emit('uipRetry', $event)"
            @vep-retry="$emit('vepRetry', $event)"
            @switch-branch="$emit('switchBranch', $event)"
            @regenerate="$emit('regenerate', $event)"
            @open-artifact="$emit('openArtifact', $event)"
          />
          </div>
        </div>
        <button v-if="showReturnToLatest" type="button" class="chat-return-latest" @click="returnToLatest">
          <ArrowDownOutlined /> 回到最新回复
        </button>
        <MessageNavigator
          :messages="messages"
          :scroll-container="messagesScrollRef"
        />
      </div>
      <div class="chat-main-input-wrap" v-if="!sessionMessageTable || (canViewUsage && sessionId)">
        <div class="chat-input-outer">
          <AgentPlanDocument v-if="!sessionMessageTable && canViewPlan && sessionId" :key="`plan-${sessionId}`" :session-id="sessionId" :is-running="isRunning"
            @open-file="inputTagPreviewHandle({ path: $event, name: 'PLAN', fullName: 'PLAN.md', extension: 'md', folderPath: '.plans' })" />
          <div v-if="sessionId && ((!sessionMessageTable && allowInstructions) || canViewUsage)" class="chat-run-actions" role="group" aria-label="会话运行信息">
            <AgentInstructions v-if="!sessionMessageTable && allowInstructions && accountStore.hasPerm('agent:chat:instruct')" v-permission="['agent:chat:instruct']"
              :key="sessionId" :session-id="sessionId" :is-running="isRunning" :is-stopping="isStopping" />
            <AgentRunUsage v-if="canViewUsage"
              v-permission="['agent:definition:list']" :key="`usage-${sessionId}`" :session-id="sessionId" :agent-id="agentId" :is-running="isRunning" />
          </div>
          <ChatInput
            v-if="!sessionMessageTable"
            :model-value="inputValue"
            :agent-id="agentId"
            :uploaded-files="uploadedFiles"
            :isRunning="isRunning"
            :allow-running-messages="allowInstructions && accountStore.hasPerm('agent:chat:instruct')"
            :is-stopping="isStopping"
            :is-sending="isSending"
            :allow-plan-mode="allowPlanMode"
            :plan-mode="planMode"
            @plan-mode="emit('planMode', $event)"
            :allow-upload-file-type="allowUploadFileType"
            :session-id="sessionId"
            :context-usage="contextUsage"
            :memory-compression-active="memoryCompressionActive"
            :mention-allowed="true"
            @inputTagPreview="inputTagPreviewHandle"
            @update:model-value="$emit('update:inputValue', $event)"
            @update:uploaded-files="$emit('update:uploadedFiles', $event)"
            @send="handleSend"
            @abort="$emit('abort')"
          >
            <template #queue>
              <ConversationQueue v-if="allowInstructions && sessionId && accountStore.hasPerm('agent:chat:instruct')"
                :key="`queue-${sessionId}`" :session-id="sessionId" :snapshot="queueSnapshot || null" :error="queueError"
                :is-running="isRunning" :is-stopping="isStopping" @changed="emit('queueChanged')" />
            </template>
          </ChatInput>
          <div v-if="!sessionMessageTable" class="text-placeholder text-xs mt-sm" style="text-align: center; margin: 5px 0;">内容由AI生成，仅供参考</div>
        </div>
      </div>
    </template>
    <!-- 输入Tag文件预览弹窗 -->
    <WorkspaceFilePreview
      v-model:visible="workspaceFilePreviewVisible"
      :file-node="workspaceFilePreviewNode"
      :session-id="sessionId as string"
    />
  </main>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-messages-area { position: relative; }
.chat-return-latest { position: absolute; z-index: 5; bottom: 14px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; gap: 6px; padding: 8px 14px; border: 1px solid var(--color-border-light, #ddd); border-radius: 20px; background: var(--color-bg-container, #fff); color: var(--color-text-regular, #555); box-shadow: 0 3px 14px #00000012; cursor: pointer; font-size: 12px; white-space: nowrap; }
.chat-return-latest:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; }

.chat-run-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0 4px 6px;
  min-width: 0;

  &:empty { display: none; }

  @media (max-width: 480px) { gap: 4px; margin-inline: 0; }
}

/* 历史消息加载提示 */
.chat-history-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px 0;
  color: #999;
  font-size: 13px;
}
</style>
