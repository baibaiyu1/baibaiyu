<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { LoadingOutlined, BulbOutlined, CopyOutlined, CheckOutlined, ToolOutlined, RightOutlined, DownOutlined } from '@ant-design/icons-vue'
import MediaPreview from '@/components/common/MediaPreview.vue'
import type { ChatImage, UploadedFileItem, KnowledgeCitationReference } from '@/types'
import KnowledgeCitations from './KnowledgeCitations.vue'
import { citationCopyText } from '@/utils/chat/citation'
import ChatImages from './ChatImages.vue'
import { attachmentImages, isImageFile } from '@/utils/chat/image'
import MediaIcon from '@/components/common/MediaIcon.vue'
import MarkdownRenderer from "@/components/markdown/MarkdownRenderer.vue";
import TaggedContentRenderer from './TaggedContentRenderer.vue';
import type { InteractionSubmitPayload } from '@/components/markdown/uip/types'

const FILE_SEP = '@==##::::##==@'

/**
 * 解析用户内容，分离文件和文本
 */
function parseUserContent(content: string): { files: UploadedFileItem[]; text: string } {
  const idx = content.indexOf(FILE_SEP)
  if (idx === -1) return { files: [], text: content }
  const prefix = content.slice(0, idx)
  const text = content.slice(idx + FILE_SEP.length)
  try {
    const parsed = JSON.parse(prefix) as { files?: UploadedFileItem[] }
    const files = Array.isArray(parsed?.files) ? parsed.files : []
    return { files, text }
  } catch {
    return { files: [], text: content }
  }
}

/** 从文件名解析扩展名（小写） */
const getExtension = (fileName: string): string => {
  const lastDot = fileName.lastIndexOf('.')
  return lastDot > -1 ? fileName.slice(lastDot + 1).toLowerCase() : ''
}

/**
 * 格式化时间显示
 * 输入格式：YYYY-MM-DD HH:mm:ss
 * - 今天：显示 HH:mm
 * - 本年非今天：显示 MM-DD HH:mm
 * - 非本年：显示 YYYY-MM-DD HH:mm
 */
const formatTime = (dateStr?: string): string => {
  if (!dateStr) return ''

  // 直接截取，避免不必要的 split 操作
  const datePart = dateStr.slice(0, 10)
  const timePart = dateStr.slice(11, 16) // HH:mm

  if (datePart.length < 10) return ''

  // 一次性解析日期部分
  const year = datePart.slice(0, 4)
  const month = datePart.slice(5, 7)
  const day = datePart.slice(8, 10)

  const now = new Date()
  const currentYear = String(now.getFullYear())

  // 今日判断：比较时间戳（最高效）
  const todayStr = `${currentYear}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`

  if (datePart === todayStr) {
    return timePart
  }

  if (year === currentYear) {
    return `${month}/${day} ${timePart}`
  }

  return `${year}/${month}/${day} ${timePart}`
}

const props = defineProps<{
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool' | 'error' | 'thinking' | 'subagent'
  content: string
  currentIndex: number
  totalMessages: number
  createdAt?: string
  agentHasResult?: boolean
  isStreaming?: boolean
  isMemoryCompression?: boolean
  readonlyInteraction?: boolean
  images?: ChatImage[]
  deferKnowledgeImages?: boolean
  citations?: KnowledgeCitationReference[]
  citationReferences?: KnowledgeCitationReference[]
  /** 仅整轮最终回复显示复制、分支与重新生成操作。 */
  showReplyActions?: boolean
  /** 分支序号（同一提问的多个候选回复，从 0 开始）；无分支时为 null */
  branchIndex?: number | null
  /** 分支总数；<=1 时不展示分支切换 */
  branchTotal?: number
  /** 是否展示「重新生成」（父消息为可重发的用户消息时） */
  canRegenerate?: boolean
  /** 运行中/停止中：禁用分支切换与重新生成 */
  branchDisabled?: boolean
}>()

const emit = defineEmits<{
  inputTagPreview: [value: unknown]
  interactionSubmit: [payload: InteractionSubmitPayload]
  uipRetry: [uipCode: string]
  vepRetry: [vepCode: string]
  /** 切换回复分支：delta = -1 上一版，1 下一版 */
  switchBranch: [delta: number]
  /** 重新生成该条回复 */
  regenerate: []
}>()

const isUser = computed(() => props.role === 'user')
const isThinking = computed(() => props.role === 'thinking')
const isAssistant = computed(() => props.role === 'assistant')
const isTool = computed(() => props.role === 'tool')
const isError = computed(() => props.role === 'error')
const isMemoryCompression = computed(() => props.isMemoryCompression)

const parsedUserContent = computed(() => parseUserContent(props.content))
const userImages = computed(() => attachmentImages(parsedUserContent.value.files))
const userFiles = computed(() => parsedUserContent.value.files.filter(file => !isImageFile(file.extension || getExtension(file.name))))
const formattedTime = computed(() => formatTime(props.createdAt))

// 预览相关状态
const previewVisible = ref(false)
const previewCurrentIndex = ref(0)

// 推理面板展开状态
const reasoningExpanded = ref(false)

watch(() => props.currentIndex === props.totalMessages - 1, (thinking) => {
    reasoningExpanded.value = thinking
}, { immediate: true })

// 工具调用面板展开状态（默认收起）
const toolExpanded = ref(false)

/** 工具调用类型 */
interface ToolCallItem {
  name: string
  totalTimes: number
  args: string
  result: string
}

/** 解析工具调用 JSON 内容 */
const parsedToolCall = computed<ToolCallItem>(() => {
  if (!props.content) return null
  try {
    return JSON.parse(props.content)
  } catch {
    return null
  }
})

// 复制成功状态（2秒内）
const copied = ref(false)

/**
 * 待复制的文本内容
 * 用户消息：文件名列表 + 文本；AI消息：仅正文（不包含推理过程）
 */
const copyText = computed(() => {
  if (isUser.value) {
    const { files, text } = parsedUserContent.value
    if (files.length === 0) return text
    const fileNames = files.map(f => f.name).join('\n')
    return text ? `${fileNames}\n${text}` : fileNames
  }
  return citationCopyText(props.content, props.citationReferences ?? [])
})

/**
 * 复制消息内容到剪贴板
 * 使用 Clipboard API，失败时降级到 execCommand
 */
async function handleCopy() {
  if (copied.value || !copyText.value) return
  const text = copyText.value
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      throw new Error('clipboard unavailable')
    }
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    try {
      document.execCommand('copy')
    } catch {
      // 复制失败静默处理
    }
    document.body.removeChild(textarea)
  }
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}

/**
 * 打开文件预览
 */
const openPreview = (index: number) => {
  previewCurrentIndex.value = index
  previewVisible.value = true
}
</script>

<template>
  <div class="chat-message" :class="[isUser ? 'chat-message-user' : 'chat-message-assistant']" :data-msg-id="isUser ? id : undefined">
    <template v-if="isUser">
      <div class="chat-message-bubble chat-message-bubble_user" style="position: relative">
        <div class="message-time">{{ formattedTime }}</div>
        <!-- 文件列表 -->
        <ChatImages v-if="userImages.length" :images="userImages" />
        <div v-if="userFiles.length > 0" class="chat-message-files">
          <div
            v-for="(item, index) in userFiles"
            :key="item.id"
            @click="openPreview(index)"
            class="chat-message-file-item"
          >
            <MediaIcon :type="(item.extension ?? getExtension(item.name)) || 'FILE'" size="19"/>
            <span class="chat-message-file-name" :title="item.name">{{ item.name }}</span>
          </div>
        </div>
        <!-- 文本内容（支持标签渲染） -->
        <span v-if="parsedUserContent.text" class="chat-message-user-content">
          <TaggedContentRenderer
            @inputTagPreview="$emit('inputTagPreview', $event)"
            :content="parsedUserContent.text" />
        </span>
        <!-- 复制按钮：悬浮显现于气泡左侧 -->
        <span
          class="msg-copy-btn msg-copy-btn--user"
          :class="{ 'is-done': copied }"
          :title="copied ? '已复制' : '复制'"
          @click="handleCopy"
        >
          <CheckOutlined v-if="copied" />
          <CopyOutlined v-else />
        </span>
      </div>
    </template>
    <template v-else-if="isThinking">
      <div class="chat-message-bubble">
        <div v-if="!agentHasResult && !content" class="chat-loading-dots">
          <span></span><span></span><span></span>
        </div>
        <!-- 推理过程面板（独立于正文显示） -->
        <div v-if="isThinking" class="chat-reasoning-panel">
          <div class="chat-reasoning-header" @click="reasoningExpanded = !reasoningExpanded">
            <span class="chat-reasoning-icon">
              <LoadingOutlined v-if="isStreaming || isMemoryCompression" spin />
              <BulbOutlined v-else />
            </span>
            <span class="chat-reasoning-title">
              {{ isMemoryCompression ? '记忆压缩中...' : (isStreaming ? '思考中...' : '思考过程') }}
            </span>
            <span class="chat-reasoning-arrow" v-if="!isMemoryCompression">
              <DownOutlined v-if="reasoningExpanded" />
              <RightOutlined v-else />
            </span>
          </div>
          <div v-if="!isMemoryCompression" class="chat-reasoning-content" :class="{ 'is-expanded': reasoningExpanded }">
            {{ content }}
          </div>
        </div>
      </div>
    </template>
    <template v-else-if="isAssistant">
      <div class="chat-message-bubble">
        <div v-if="!agentHasResult && !content" class="chat-loading-dots">
          <span></span><span></span><span></span>
        </div>
        <!-- 正文内容 -->
        <div v-if="isAssistant" class="chat-md-content">
          <MarkdownRenderer
            :content="content"
            :images="images"
            :defer-knowledge-images="deferKnowledgeImages"
            :citations="citationReferences"
            :is-streaming="isStreaming"
            :disabled="currentIndex !== totalMessages - 1 || readonlyInteraction"
            :readonly-interaction="readonlyInteraction"
            @interaction-submit="$emit('interactionSubmit', $event)"
            @uip-retry="$emit('uipRetry', $event)"
            @vep-retry="$emit('vepRetry', $event)" />
          <KnowledgeCitations v-if="citations?.length" :citations="citations" />
          <!-- 操作只占最终回复的一行，中间步骤不渲染操作或预留空白。 -->
          <div v-if="showReplyActions && content && !isStreaming" class="msg-reply-actions" :class="{ 'is-done': copied }">
            <button
              type="button"
              class="msg-copy-btn msg-copy-btn--assistant"
              :class="{ 'is-done': copied }"
              :title="copied ? '已复制' : '复制'"
              @click="handleCopy"
            >
              <CheckOutlined v-if="copied" />
              <CopyOutlined v-else />
            </button>
            <!-- 回复分支操作：上一版 / 下一版 与 重新生成 -->
            <span
              v-if="content && !isStreaming && ((branchTotal && branchTotal > 1) || canRegenerate)"
              class="msg-branch-bar"
            >
              <template v-if="branchTotal && branchTotal > 1">
                <button
                  type="button"
                  class="msg-branch-btn"
                  :disabled="branchDisabled || branchIndex === null || branchIndex === undefined || branchIndex <= 0"
                  title="上一版"
                  @click="$emit('switchBranch', -1)"
                >‹</button>
                <span class="msg-branch-index">{{ (branchIndex ?? 0) + 1 }}/{{ branchTotal }}</span>
                <button
                  type="button"
                  class="msg-branch-btn"
                  :disabled="branchDisabled || branchIndex === null || branchIndex === undefined || branchIndex >= branchTotal - 1"
                  title="下一版"
                  @click="$emit('switchBranch', 1)"
                >›</button>
              </template>
              <button
                v-if="canRegenerate"
                type="button"
                class="msg-branch-text"
                :disabled="branchDisabled"
                title="重新生成这条回复"
                @click="$emit('regenerate')"
              >重新生成</button>
            </span>
          </div>

        </div>
      </div>
    </template>
    <template v-else-if="isTool">
      <div class="chat-message-bubble">
        <div class="chat-tool-panel">
          <!-- 可点击的头部：图标 + 标题 + 展开/收起箭头 -->
          <div class="chat-tool-header" @click="toolExpanded = !toolExpanded">
            <span class="chat-tool-header-icon"><ToolOutlined /></span>
            <span class="chat-tool-header-title">
              工具调用
            </span>
            <span class="chat-tool-header-arrow">
              <DownOutlined v-if="toolExpanded" />
              <RightOutlined v-else />
            </span>
          </div>

          <!-- 展开后的工具调用列表 -->
          <div class="chat-tool-body" :class="{ 'is-expanded': toolExpanded }">
            <!-- JSON 解析成功：结构化展示每个工具调用 -->
            <template v-if="parsedToolCall">
              <div class="chat-tool-item-header">
                <span class="chat-tool-item-name">{{ parsedToolCall.name }}</span>
                <span class="chat-tool-item-time">{{ parsedToolCall.totalTimes }}ms</span>
              </div>
              <div v-if="parsedToolCall.args && parsedToolCall.args !== '{}'">
                <pre class="chat-tool-item-code">{{ parsedToolCall.args }}</pre>
              </div>
              <div v-if="parsedToolCall.result">
                <pre class="chat-tool-item-code">{{ parsedToolCall.result }}</pre>
              </div>
            </template>
            <!-- JSON 解析失败：降级为原始文本 -->
            <pre v-else class="chat-tool-raw">{{ content }}</pre>
          </div>
        </div>
      </div>
    </template>
    <template v-else-if="isError">
      <div class="chat-message-bubble">
        <div class="chat-md-content">
          <span class="error-text">{{ content }}</span>
        </div>
      </div>
    </template>
    <!-- 媒体预览组件 -->
    <MediaPreview
      v-if="previewVisible"
      v-model:visible="previewVisible"
      :items="userFiles"
      :current-index="previewCurrentIndex"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.message-time {
  position: absolute;
  top: -18px;
  right: 3px;
  width: 150px;
  text-align: end;
  font-size: var(--font-size-xs);
  color: #d2d2d2;
}

.error-text {
  color: tomato;
}

.chat-message-files {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.chat-message-file-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 280px;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.6);
  border-radius: var(--border-radius-md);
  font-size: var(--font-size-sm);
  cursor: pointer;
  &:hover {
    background: rgba(255, 255, 255, 0.9);
  }
}

.chat-message-file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/**
 * 产物卡片区：跟在回复正文下方
 */
.msg-artifacts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

/**
 * 回复分支操作条：与复制按钮同一行，常态隐藏、悬停渐显
 */
.msg-reply-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.chat-message-bubble:hover .msg-reply-actions,
.msg-reply-actions:focus-within,
.msg-reply-actions.is-done {
  opacity: 1;
}

.msg-branch-bar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.msg-branch-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  color: #6b7280;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;

  &:hover:not(:disabled) {
    color: #4449d0;
    background: #eef1ff;
  }

  &:disabled {
    color: #c9cfda;
    cursor: not-allowed;
  }
}

.msg-branch-index {
  color: #8c98a8;
  font-size: 11px;
}

.msg-branch-text {
  padding: 0 4px;
  color: #6b7280;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 11px;

  &:hover:not(:disabled) {
    color: #4449d0;
  }

  &:disabled {
    color: #c9cfda;
    cursor: not-allowed;
  }
}

/**
 * 复制按钮：悬浮显现 + 图标状态反馈
 * 常态隐藏，鼠标悬停消息气泡时渐显，点击后图标切换为对勾并保持2秒
 */
.msg-copy-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--border-radius-sm);
  font-size: 13px;
  color: #a0a4ab;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.2s ease, color 0.15s ease, background-color 0.15s ease;

  &:hover {
    color: #4a4f57;
  }

  &.is-done {
    color: #52c41a;
    opacity: 1;
  }
}

.msg-copy-btn--user {
  position: absolute;
  right: 0;
  bottom: -25px;
}

.msg-copy-btn--assistant {
  padding: 0;
  border: 0;
  background: transparent;
  opacity: 1;
}

/* 确保气泡作为绝对定位参照 */
.chat-message-assistant .chat-message-bubble {
  position: relative;
}

/* 悬停消息气泡时显示复制按钮 */
.chat-message-bubble:hover .msg-copy-btn {
  opacity: 1;
}
</style>
