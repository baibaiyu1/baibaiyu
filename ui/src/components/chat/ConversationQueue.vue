<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import { DeleteOutlined, EditOutlined, EnterOutlined, LoadingOutlined, OrderedListOutlined, PaperClipOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { cancelQueuedMessage, editQueuedMessage, steerQueuedMessage, continueQueue, type QueuedMessage, type QueueSnapshot } from '@/api/agui/queue'

const props = defineProps<{ sessionId: string; snapshot: QueueSnapshot | null; isRunning: boolean; isStopping?: boolean; error?: string }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
const editing = ref<string | null>(null)
const draft = ref('')
const editor = ref<{ focus: () => void } | null>(null)
const busy = ref<string | null>(null)
const localError = ref('')
// 只覆盖服务端已确认的操作，刷新失败时也不把已删除或已插队的消息重新显示出来。
const confirmed = ref<Record<string, Partial<QueuedMessage>>>({})
const queued = computed(() => (props.snapshot?.messages ?? [])
  .map(item => ({ ...item, ...confirmed.value[item.id] }))
  .filter(item => item.state === 'QUEUED'))

watch(() => props.snapshot, value => {
  for (const [id, patch] of Object.entries(confirmed.value)) {
    const item = value?.messages.find(item => item.id === id)
    if (!item || item.state !== 'QUEUED' || Object.entries(patch).every(([key, value]) => item[key as keyof QueuedMessage] === value)) delete confirmed.value[id]
  }
})

function setEditor(value: unknown) { editor.value = value as { focus: () => void } | null }

watch(queued, items => {
  if (editing.value && !items.some(item => item.id === editing.value)) editing.value = null
})

function steerUnavailable(item: QueuedMessage) {
  if (!props.isRunning || props.isStopping || props.snapshot?.state !== 'RUNNING' || !props.snapshot.runId) return '当前任务暂不接收插队消息'
  if (item.options.fileIds.length) return '带附件的消息暂不支持插队'
  if (item.content.length > 4000) return '插队消息最多 4000 字，请先编辑'
  return ''
}

async function action(id: string, fn: () => Promise<void>) {
  if (busy.value) return
  busy.value = id
  localError.value = ''
  try { await fn(); emit('changed') }
  catch (e) { localError.value = e instanceof Error ? e.message : '操作失败，请重试' }
  finally { busy.value = null }
}

async function edit(item: QueuedMessage) {
  editing.value = item.id
  draft.value = item.content
  localError.value = ''
  await nextTick()
  editor.value?.focus()
}

function save(item: QueuedMessage) {
  const content = draft.value.trim()
  if (!content || content.length > 16000) return
  return action(item.id, async () => {
    await editQueuedMessage(props.sessionId, item.id, content)
    confirmed.value[item.id] = { content }
    editing.value = null
  })
}

function remove(item: QueuedMessage) {
  return action(item.id, async () => {
    await cancelQueuedMessage(props.sessionId, item.id)
    confirmed.value[item.id] = { state: 'CANCELLED' }
  })
}

function steer(item: QueuedMessage) {
  const runId = props.snapshot?.runId
  if (!runId || steerUnavailable(item)) return
  return action(item.id, async () => {
    await steerQueuedMessage(props.sessionId, item.id, runId)
    confirmed.value[item.id] = { state: 'STEERED' }
    message.success('已插队，将在下一次推理前生效')
  })
}
</script>

<template>
  <section v-if="queued.length || error || localError" class="conversation-queue" aria-label="待发送消息">
    <ol v-if="queued.length" class="queue-list">
      <li v-for="item in queued" :key="item.id" class="queue-item" :data-queue-id="item.id" :aria-busy="busy === item.id">
        <div v-if="editing === item.id" class="queue-editor">
          <ATextarea :ref="setEditor" v-model:value="draft" aria-label="编辑待发送消息" :maxlength="16000"
            :auto-size="{ minRows: 2, maxRows: 5 }" :disabled="!!busy"
            @keydown.ctrl.enter.prevent="save(item)" @keydown.meta.enter.prevent="save(item)" @keydown.esc.prevent="editing = null" />
          <div class="queue-edit-actions">
            <span>{{ draft.length }} / 16000</span>
            <AButton size="small" :disabled="!!busy" @click="editing = null">取消</AButton>
            <AButton size="small" type="primary" :loading="busy === item.id" :disabled="!!busy || !draft.trim()" @click="save(item)">保存</AButton>
          </div>
        </div>
        <template v-else>
          <LoadingOutlined v-if="busy === item.id" spin class="queue-marker" />
          <OrderedListOutlined v-else class="queue-marker" aria-hidden="true" />
          <span class="queue-content" :title="item.content">{{ item.content }}</span>
          <ATooltip v-if="item.options.fileIds.length" :title="`${item.options.fileIds.length} 个附件`">
            <span class="queue-attachment"><PaperClipOutlined />{{ item.options.fileIds.length }}</span>
          </ATooltip>
          <div class="queue-actions">
            <ATooltip :title="steerUnavailable(item) || '在当前任务的下一次推理前发送'">
              <span class="queue-action-wrap">
                <button type="button" class="queue-action queue-steer" aria-label="插队"
                  :disabled="!!busy || !!steerUnavailable(item)" @click="steer(item)"><EnterOutlined aria-hidden="true" /><span>插队</span></button>
              </span>
            </ATooltip>
            <ATooltip title="编辑">
              <button type="button" class="queue-action" aria-label="编辑消息" :disabled="!!busy" @click="edit(item)"><EditOutlined /></button>
            </ATooltip>
            <ATooltip title="删除">
              <button type="button" class="queue-action queue-delete" aria-label="删除消息" :disabled="!!busy" @click="remove(item)"><DeleteOutlined /></button>
            </ATooltip>
          </div>
        </template>
      </li>
    </ol>
    <div v-if="queued.length && snapshot?.paused" class="queue-notice">
      <span>{{ snapshot.state === 'INTERRUPTED' ? '任务已中断，待发送消息已暂停' : '待发送消息已暂停' }}</span>
      <AButton v-if="['IDLE', 'INTERRUPTED'].includes(snapshot.state)" type="link" size="small" :loading="busy === 'continue'"
        :disabled="!!busy" @click="action('continue', async () => { await continueQueue(sessionId) })">继续发送</AButton>
    </div>
    <div v-else-if="queued.length && snapshot?.state === 'WAITING'" class="queue-notice">等待当前任务完成审批后继续发送</div>
    <p v-if="error || localError" class="queue-error" role="alert">{{ localError || error }}</p>
  </section>
</template>

<style scoped>
.conversation-queue { width: calc(100% - 24px); min-width: 0; margin-bottom: -14px; padding-bottom: 14px; border: 1px solid var(--color-border-light, #e2e5ea); border-bottom: 0; border-radius: 16px 16px 0 0; background: #f5f6f8; font-size: 13px; }
.queue-list { list-style: none; margin: 0; padding: 0; max-height: min(240px, 28dvh); overflow-y: auto; overscroll-behavior: contain; }
.queue-item { display: flex; align-items: center; gap: 10px; padding: 8px 12px; min-height: 44px; }
.queue-item + .queue-item { border-top: 1px solid var(--color-border-extra-light, #e8ebef); }
.queue-marker { flex-shrink: 0; color: #8792a2; font-size: 13px; }
.queue-content { flex: 1; min-width: 0; color: var(--color-text-primary, #334155); line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.queue-attachment { display: inline-flex; align-items: center; gap: 3px; flex-shrink: 0; color: #8792a2; font-size: 12px; }
.queue-actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.queue-action-wrap { display: inline-flex; }
.queue-action { display: inline-flex; align-items: center; justify-content: center; gap: 4px; min-width: 28px; min-height: 28px; padding: 3px 6px; border: 0; border-radius: 6px; background: transparent; color: var(--color-text-regular, #64748b); cursor: pointer; font-size: 13px; white-space: nowrap; transition: background 0.15s, color 0.15s; }
.queue-action:hover:not(:disabled) { background: #e6eaf0; color: #0f74ff; }
.queue-delete:hover:not(:disabled) { background: #ffeded; color: #cf1322; }
.queue-action:disabled { opacity: 0.4; cursor: not-allowed; }
.queue-action:focus-visible { outline: 2px solid #0f74ff; outline-offset: 1px; }
.queue-steer { font-size: 12px; }
.queue-editor { width: 100%; min-width: 0; }
.queue-editor :deep(textarea) { font-size: 13px; border-radius: 8px; }
.queue-edit-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.queue-edit-actions > span { flex: 1; color: #8792a2; font-size: 11px; }
.queue-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 4px 12px 8px; color: var(--color-text-secondary, #798597); font-size: 12px; }
.queue-error { margin: 0; padding: 6px 12px 10px; color: #cf1322; line-height: 1.6; overflow-wrap: anywhere; }
@media (max-width: 480px) {
  .conversation-queue { width: calc(100% - 16px); }
  .queue-item { gap: 6px; padding: 7px 8px; }
  .queue-marker { display: none; }
  .queue-action { min-width: 30px; min-height: 32px; }
}
</style>
