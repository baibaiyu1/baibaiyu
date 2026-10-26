<script setup lang="ts">
/**
 * 聊天输入框工具栏组件
 * 负责计划模式入口、@按钮、上传按钮、发送/中断按钮
 *
 * @component
 */
import { computed } from 'vue'
import {
  ArrowUpOutlined,
  CheckOutlined,
  FileSearchOutlined,
  LoadingOutlined,
  PaperClipOutlined
} from '@ant-design/icons-vue'
import ContextUsageIndicator from './ContextUsageIndicator.vue'
import type { ContextUsageEvent } from '@/types'

const props = withDefaults(
  defineProps<{
    isRunning?: boolean
    isStopping?: boolean
    isSending?: boolean
    allowRunningMessages?: boolean
    allowPlanMode?: boolean
    planMode?: boolean
    /** 是否允许触发发送（综合上传中、内容、附件等条件） */
    canSend: boolean
    hasContent: boolean
    mentionAllowed?: boolean
    allowUploadFileType?: string[]
    contextUsage?: ContextUsageEvent['value'] | null
    memoryCompressionActive?: boolean
  }>(),
  {
    isRunning: false,
    isStopping: false,
    mentionAllowed: false
  }
)

const emit = defineEmits<{
  (e: 'mentionTrigger'): void
  (e: 'pickFile'): void
  (e: 'send'): void
  (e: 'abort'): void
  (e: 'planMode', value: boolean): void
}>()

const isAbortAction = computed(() => props.isStopping || (props.isRunning && (!props.allowRunningMessages || !props.hasContent)))
const sendTooltip = computed(() => props.isStopping ? '正在停止…' : isAbortAction.value ? '停止生成'
  : props.isSending ? '正在发送…' : props.canSend ? '发送消息' : props.hasContent ? '附件上传完成后可发送' : '输入内容后可发送')

/** 多模态类型扩展名集合（用于 tooltip 分类） */
const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico'])
const AUDIO_EXTS = new Set(['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac', 'wma', 'mpeg'])
const VIDEO_EXTS = new Set(['mp4', 'webm', 'mov', 'mkv', 'avi', 'flv', 'm3u8', 'mpeg'])
const DOC_EXTS = new Set([
  'doc', 'docx', 'pdf', 'txt', 'md',
  'xlsx', 'xls', 'csv', 'pptx', 'ppt'
])

/** 上传按钮 tooltip：按类别聚合展示，避免扩展名列表过长 */
const uploadTooltip = computed(() => {
  const types = props.allowUploadFileType
  if (!types?.length) return '点击上传文件'

  const parts: string[] = []
  if (types.some((t) => IMAGE_EXTS.has(t))) parts.push('图片')
  if (types.some((t) => AUDIO_EXTS.has(t))) parts.push('音频')
  if (types.some((t) => VIDEO_EXTS.has(t))) parts.push('视频')
  if (types.some((t) => DOC_EXTS.has(t))) parts.push('文档')

  return parts.length > 0 ? `点击上传文件（${parts.join('、')}）` : '点击上传文件'
})
</script>

<template>
  <div class="chat-input-toolbar">
    <div class="chat-input-toolbar-left">
      <ATooltip v-if="allowPlanMode" placement="top" :title="planMode ? '计划模式已开启：先只读调查并生成方案，批准后再执行' : '开启计划模式：先生成方案，批准后再执行'">
        <button
          type="button"
          class="chat-toolbar-btn chat-plan-mode-btn"
          :class="{ 'is-active': planMode }"
          :aria-pressed="!!planMode"
          :disabled="isStopping"
          @click="emit('planMode', !planMode)"
        >
          <FileSearchOutlined aria-hidden="true" />
          <span>计划模式</span>
          <CheckOutlined v-if="planMode" class="plan-mode-check" aria-hidden="true" />
        </button>
      </ATooltip>
    </div>
    <div class="chat-input-toolbar-right">
      <!-- 上下文使用情况提示 -->
      <ContextUsageIndicator
        :usage="contextUsage"
        :compression-active="memoryCompressionActive"
      />
      <!-- @ 添加上下文按钮 -->
      <ATooltip placement="bottom" title="添加上下文">
        <button
          :disabled="isStopping || !mentionAllowed"
          type="button"
          class="chat-toolbar-btn chat-toolbar-btn-icon chat-toolbar-btn-circle"
          @mousedown.prevent
          @click="emit('mentionTrigger')"
        >
          @
        </button>
      </ATooltip>
      <ATooltip placement="bottom" :title="uploadTooltip">
        <button
          :disabled="isStopping"
          type="button"
          class="chat-toolbar-btn chat-toolbar-btn-icon chat-toolbar-btn-circle chat-attachment-btn"
          @click="emit('pickFile')"
        >
          <PaperClipOutlined />
        </button>
      </ATooltip>
      <ATooltip placement="top" :title="sendTooltip">
        <button
          type="button"
          class="chat-send-btn-inner"
          :class="{ 'is-stop': isAbortAction }"
          :aria-label="isAbortAction ? '停止生成' : '发送消息'"
          :disabled="isStopping || (!isAbortAction && !canSend)"
          @click="isAbortAction ? emit('abort') : emit('send')"
        >
          <LoadingOutlined v-if="isStopping || isSending" spin />
          <span v-else-if="isAbortAction" class="stop-square" aria-hidden="true" />
          <ArrowUpOutlined v-else />
        </button>
      </ATooltip>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  min-height: 36px;
  gap: 8px;
  flex-wrap: wrap;
}

.chat-input-toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.chat-input-toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.chat-toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background-color: #f5f5f5;
  cursor: pointer;
  color: var(--color-text-regular, #64748b);
  transition: color 0.2s ease, background-color 0.2s ease;
  border-radius: var(--border-radius-md);
  flex-shrink: 0;

  &:hover {
    color: $chat-primary;
    background-color: rgba($chat-primary, 0.06);
  }

  &.is-active {
    color: $chat-primary;
    background-color: rgba($chat-primary, 0.1);
    font-weight: 500;
  }

  &:disabled,
  &[disabled] {
    opacity: 0.45;
    cursor: not-allowed;
    &:hover {
      cursor: not-allowed;
      color: var(--color-text-regular, #64748b);
      background-color: transparent;
    }
  }

  &:focus-visible { outline: 2px solid $chat-primary; outline-offset: 2px; }
}

.chat-plan-mode-btn {
  gap: 6px;
  min-height: 32px;
  padding: 5px 10px;
  border: 1px solid var(--color-border-light, #e2e8f0);
  border-radius: 16px;
  background: #fff;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;

  > .anticon { font-size: 14px; }
  &.is-active { border-color: rgba($chat-primary, 0.25); }
  .plan-mode-check { font-size: 11px; }
}
.chat-attachment-btn { margin-right: 4px; }

.chat-toolbar-btn-text {
  padding: 6px 10px;
  font-size: var(--font-size-sm);
}

.chat-toolbar-btn-icon {
  width: 32px;
  height: 32px;
  font-size: 16px;
}

.chat-toolbar-btn-circle {
  border-radius: 50%;
}

.chat-send-btn-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: $chat-primary;
  border: none;
  color: white;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover:not(:disabled) {
    transform: scale(1.05);
  }

  &:disabled {
    background-color: #e0e0e0;
    cursor: not-allowed;
    opacity: 0.6;
  }

  &.is-stop {
    border: 1px solid var(--color-border-light, #dce1e8);
    background: transparent;
    color: var(--color-text-primary, #334155);
    &:hover:not(:disabled) { background: #eef1f5; }
  }
  .stop-square { width: 11px; height: 11px; border-radius: 2px; background: currentColor; }
}
.chat-send-btn-inner:focus-visible { outline: 2px solid $chat-primary; outline-offset: 2px; }

@media (max-width: 480px) {
  .chat-input-toolbar { gap: 6px; }
  .chat-input-toolbar-left, .chat-input-toolbar-right { gap: 2px; }
  .chat-toolbar-btn-icon { width: 30px; }
  .chat-plan-mode-btn { padding-inline: 8px; }
  .chat-attachment-btn { margin-right: 0; }
}
</style>
