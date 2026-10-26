<script setup lang="ts">
import ChatInput from './ChatInput.vue'
import { computed } from "vue";

const props = defineProps<{
  messageSize: number
  headline: string
  inputValue: string
  agentId: string
  description?: string
  uploadedFiles?: import('@/types').UploadedFileItem[]
  isRunning?: boolean
  isSending?: boolean
  allowPlanMode?: boolean
  planMode?: boolean
  allowUploadFileType?: string[]
  sessionId?: string | null
  mentionAllowed?: boolean
  hasCodeExecutionConfig?: boolean
  /** 预设问题（开场白配置） */
  openingQuestions?: string[]
}>()

const needInit = computed(() => {
  // 创建或选中会话后即可输入，不依赖根消息是否已经加载。
  return props.hasCodeExecutionConfig && !props.sessionId
})

const visibleQuestions = computed(() => [...new Set((props.openingQuestions ?? [])
  .map(question => question.trim()).filter(Boolean))].slice(0, 6))

defineEmits<{
  (e: 'update:inputValue', value: string): void
  (e: 'update:uploadedFiles', value: import('@/types').UploadedFileItem[]): void
  (e: 'send'): void
  (e: 'planMode', value: boolean): void
  (e: 'newSession'): void
  (e: 'pickQuestion', question: string): void
}>()
</script>

<template>
  <div class="chat-welcome">
    <h2 class="chat-welcome-title" :title="headline">{{ headline }}</h2>
    <p v-if="description" class="chat-welcome-desc" :title="description">{{ description }}</p>
    <div v-if="visibleQuestions.length" class="chat-opening-questions" role="group" aria-label="推荐提问">
      <button
        v-for="question in visibleQuestions"
        :key="question"
        type="button"
        class="chat-opening-question"
        :title="question"
        :disabled="isRunning || isSending"
        @click="$emit('pickQuestion', question)"
      >
        {{ question }}
      </button>
    </div>
    <div class="chat-input-outer chat-welcome-input">
      <ChatInput
        :model-value="inputValue"
        :agent-id="agentId"
        :uploaded-files="uploadedFiles"
        :isRunning="isRunning"
        :is-sending="isSending"
        :allow-plan-mode="allowPlanMode"
        :plan-mode="planMode"
        @plan-mode="$emit('planMode', $event)"
        :allow-upload-file-type="allowUploadFileType"
        :session-id="sessionId"
        :mention-allowed="mentionAllowed"
        :need-init="needInit"
        @update:model-value="$emit('update:inputValue', $event)"
        @update:uploaded-files="$emit('update:uploadedFiles', $event)"
        @send="$emit('send')"
        @new-session="$emit('newSession')"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;
</style>
