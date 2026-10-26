<template>
  <div class="uip-renderer">
    <!-- 流式 JSON 不完整 → 优雅占位 -->
    <UipSkeleton v-if="!parsed && isStreaming" />

    <!-- 非流式 JSON 无效 → 降级显示 -->
    <div v-else-if="!parsed" class="uip-fallback">
      <div class="uip-fallback-hint">[UIP 解析失败]</div>
      <pre class="uip-fallback-code">{{ truncatedCode }}</pre>
      <button
        v-if="!disabled"
        class="uip-retry-btn"
        :disabled="retrying"
        @click="onRetry"
      >
        <ReloadOutlined /> {{ retrying ? '重试中...' : '重新生成' }}
      </button>
    </div>

    <!-- interaction 为空 → 降级显示 -->
    <div v-else-if="!parsed?.interaction" class="uip-fallback">
      <div class="uip-fallback-hint">[UIP 交互内容为空]</div>
      <pre class="uip-fallback-code">{{ truncatedCode }}</pre>
      <button
        v-if="!disabled"
        class="uip-retry-btn"
        :disabled="retrying"
        @click="onRetry"
      >
        <ReloadOutlined /> {{ retrying ? '重试中...' : '重新生成' }}
      </button>
    </div>

    <!-- form 交互 -->
    <FormRenderer
      v-else-if="parsed?.interaction && parsed.interaction.type === 'form'"
      :interaction="parsed?.interaction"
      :disabled="disabled"
      @submit="onSubmit"
    />

    <!-- choice 交互 -->
    <ChoiceRenderer
      v-else-if="parsed?.interaction && parsed.interaction.type === 'choice'"
      :interaction="parsed?.interaction"
      :disabled="disabled"
      @submit="onSubmit"
    />

    <!-- confirm 交互 -->
    <ConfirmRenderer
      v-else-if="parsed?.interaction && parsed.interaction.type === 'confirm'"
      :interaction="parsed?.interaction"
      :disabled="disabled"
      @submit="onSubmit"
    />

    <!-- 未知类型 → 降级 -->
    <div v-else class="uip-fallback">
      <div class="uip-fallback-hint">[UIP 未知交互类型]</div>
      <button
        v-if="!disabled"
        class="uip-retry-btn"
        :disabled="retrying"
        @click="onRetry"
      >
        <ReloadOutlined /> {{ retrying ? '重试中...' : '重新生成' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import type { InteractionSubmitPayload } from './types'
import { parseUIPJson } from '@/utils/chat/uip.ts'
import UipSkeleton from './UIPSkeleton.vue'
import FormRenderer from './FormRenderer.vue'
import ChoiceRenderer from './ChoiceRenderer.vue'
import ConfirmRenderer from './ConfirmRenderer.vue'

const props = defineProps<{
  /** uip 代码块原始内容（不含 ``` 标记） */
  code: string
  /** 是否处于流式输出阶段 */
  isStreaming?: boolean
  /** 历史消息只读模式（或已提交只读） */
  disabled?: boolean
}>()

const emit = defineEmits<{
  submit: [payload: InteractionSubmitPayload]
  retry: [code: string]
}>()

const parsed = computed(() => parseUIPJson(props.code))

const truncatedCode = computed(() => {
  if (props.code.length <= 50) return props.code
  return props.code.slice(0, 50) + '...'
})

const retrying = ref(false)

function onSubmit(data: Record<string, unknown>) {
  const item = parsed.value?.interaction
  if (!item) return
  emit('submit', {
    interactionId: item.id,
    type: item.type,
    data,
    uipCode: props.code,
  })
}

/** 触发重试，请求智能体重新生成交互卡片 */
function onRetry() {
  if (retrying.value) return
  retrying.value = true
  emit('retry', props.code)
  // 5秒后重置状态，允许再次点击
  setTimeout(() => { retrying.value = false }, 5000)
}
</script>

<style scoped>
.uip-renderer {
  margin: 4px 0;
}

.uip-fallback {
  margin: 8px 0;
  border: 1px solid #ffe58f;
  border-radius: 8px;
  padding: 10px 14px;
  background: #fffbe6;
}

.uip-fallback-hint {
  font-size: 12px;
  color: #d48806;
  margin-bottom: 6px;
}

.uip-fallback-code {
  font-size: 11px;
  color: #8c8c8c;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 120px;
  overflow-y: auto;
  margin: 0;
  line-height: 1.5;
}

.uip-retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding: 4px 12px;
  font-size: 12px;
  color: #1677ff;
  background: #fff;
  border: 1px solid #1677ff;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover:not(:disabled) {
    color: #fff;
    background: #1677ff;
  }

  &:disabled {
    color: #bfbfbf;
    border-color: #d9d9d9;
    cursor: not-allowed;
  }
}
</style>
