<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { CommentOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useAgentInstructions } from '@/composables/chat/useAgentInstructions'
import ChatRunPanel from './ChatRunPanel.vue'

const props = defineProps<{ sessionId: string; isRunning: boolean; isStopping?: boolean }>()
const { inbox, error, refresh } = useAgentInstructions(toRef(props, 'sessionId'), toRef(props, 'isRunning'))
const open = ref(false)
const scope = ref<'current' | 'all'>('current')
const canSend = computed(() => props.isRunning && inbox.value?.accepting && !props.isStopping)
const pendingCount = computed(() => inbox.value?.messages.filter(item => item.runId === inbox.value?.runId && ['RECEIVED', 'INJECTING'].includes(item.state)).length ?? 0)
const currentCount = computed(() => inbox.value?.messages.filter(item => item.runId === inbox.value?.runId).length ?? 0)
const messages = computed(() => {
  const items = inbox.value?.messages ?? []
  return scope.value === 'current' ? items.filter(item => item.runId === inbox.value?.runId) : items
})
const labels = {
  RECEIVED: '等待生效', INJECTING: '正在加入推理', APPLIED: '已加入推理',
  CANCELLED: '未应用', EXPIRED: '已过期', UNCONFIRMED: '状态未确认',
}
const descriptions = {
  RECEIVED: '已接收，将在下一次推理前加入。', INJECTING: '正在交接到模型输入。',
  APPLIED: '已进入模型输入，不代表已经完成处理。',
  CANCELLED: '任务结束、停止或等待审批，未应用这条要求。',
  EXPIRED: '等待超过有效期，未应用这条要求。', UNCONFIRMED: '交接时发生中断，不会自动重放。',
}
const colors = { RECEIVED: 'processing', INJECTING: 'processing', APPLIED: 'success', CANCELLED: 'default', EXPIRED: 'default', UNCONFIRMED: 'warning' }

watch(open, visible => { if (visible) void refresh() })
watch(() => inbox.value?.runId, runId => { scope.value = runId ? 'current' : 'all' })
</script>

<template>
  <ChatRunPanel
    v-if="inbox?.messages.length || error"
    v-model:open="open"
    title="插队记录"
    :attention="pendingCount > 0"
  >
    <template #icon><CommentOutlined /></template>
    <template #summary>
      <span v-if="error">刷新失败</span>
      <span v-else-if="pendingCount">{{ pendingCount }} 条待生效</span>
      <span v-else-if="inbox?.messages.length">{{ inbox.messages.length }} 条</span>
    </template>
    <template #extra>
      <AButton type="text" size="small" aria-label="刷新插队记录" @click="refresh"><ReloadOutlined /></AButton>
    </template>
    <section class="instruction-intro">
      <div class="intro-heading">
        <span class="live-dot" :class="{ 'is-active': canSend }" aria-hidden="true" />
        <strong>{{ canSend ? '当前任务可接收插队消息' : '当前任务暂不接收插队消息' }}</strong>
      </div>
      <p>发送消息后，点击待发送消息旁的“插队”，将在下一次推理前加入当前任务。</p>
    </section>
    <AAlert v-if="error" class="instruction-error" type="error" show-icon :message="error" role="alert" />
    <div class="instruction-history-heading">
      <strong>插队记录</strong>
      <span>最近 100 条</span>
    </div>
    <ARadioGroup v-model:value="scope" class="history-filter" size="small" button-style="solid">
      <ARadioButton v-if="inbox?.runId" value="current">当前任务 · {{ currentCount }}</ARadioButton>
      <ARadioButton value="all">全部记录 · {{ inbox?.messages.length ?? 0 }}</ARadioButton>
    </ARadioGroup>
    <AEmpty v-if="!messages.length" :description="scope === 'current' ? '当前任务还没有插队消息' : '暂无插队记录'" />
    <ol v-else class="instruction-list" aria-label="插队消息记录">
      <li v-for="(item, index) in messages" :key="item.id" class="instruction-item">
        <div class="instruction-meta">
          <span :title="item.runId">{{ item.runId === inbox?.runId ? '当前任务' : '此前任务' }}<span class="instruction-index"> · {{ index + 1 }}</span></span>
          <ATag :color="colors[item.state]" :bordered="false">{{ labels[item.state] }}</ATag>
        </div>
        <p class="instruction-content">{{ item.content }}</p>
        <p class="instruction-state">{{ descriptions[item.state] }}</p>
      </li>
    </ol>
    <p class="instruction-footnote">每条最多 4000 字，每轮最多 50 条。已执行的操作不会撤回。</p>
  </ChatRunPanel>
</template>

<style scoped>
.instruction-intro { padding: 16px; border: 1px solid #e0ebfb; border-radius: 12px; background: #f7faff; }
.intro-heading { display: flex; align-items: center; gap: 8px; color: var(--color-text-primary, #1e293b); }
.live-dot { width: 7px; height: 7px; border-radius: 50%; background: #94a3b8; }
.live-dot.is-active { background: #0f74ff; }
.instruction-intro p { margin: 8px 0 12px; color: var(--color-text-regular, #64748b); font-size: 13px; line-height: 1.8; }
.instruction-error { margin-top: 16px; }
.instruction-history-heading { display: flex; justify-content: space-between; gap: 12px; margin: 24px 0 12px; }
.instruction-history-heading > span, .instruction-index { color: #8792a2; font-size: 12px; }
.history-filter { margin-bottom: 16px; }
.instruction-list { list-style: none; margin: 0; padding: 0; }
.instruction-item { padding: 16px 0; border-bottom: 1px solid var(--color-border-extra-light, #eef0f3); }
.instruction-item:first-child { padding-top: 0; }
.instruction-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--color-text-regular, #64748b); font-size: 12px; }
.instruction-meta :deep(.ant-tag) { margin: 0; }
.instruction-content { margin: 10px 0 6px; color: var(--color-text-primary, #1e293b); white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.instruction-state, .instruction-footnote { margin: 0; color: var(--color-text-regular, #64748b); font-size: 12px; line-height: 1.8; }
.instruction-footnote { margin-top: 20px; }
</style>
