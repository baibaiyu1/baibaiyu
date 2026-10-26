import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { getQueue, enqueueMessage, type QueueOptions, type QueueSnapshot } from '@/api/agui/queue'
import { createClientId } from '@/utils/clientId'

/** 持久队列由服务端执行；前端轮询只用于更新列表和连接新一轮。 */
export function useConversationQueue(sessionId: Ref<string | null>, enabled: Ref<boolean>, onChange: (value: QueueSnapshot) => void) {
  const snapshot = ref<QueueSnapshot | null>(null)
  const error = ref('')
  const sending = ref(false)
  let generation = 0, sequence = 0, polling = 0, timer: ReturnType<typeof setTimeout> | undefined, disposed = false
  let pending: { key: string; id: string } | undefined
  async function refresh() {
    const sid = sessionId.value, version = generation, request = ++sequence
    if (!sid || !enabled.value || disposed) return
    try {
      const value = await getQueue(sid)
      if (generation !== version || disposed || request !== sequence) return
      snapshot.value = value
      error.value = ''
      onChange(value)
    } catch (e) { if (generation === version && !disposed && request === sequence) error.value = e instanceof Error ? e.message : '无法刷新队列' }
  }
  async function poll() {
    const version = generation
    const pollId = ++polling
    await refresh()
    if (!disposed && version === generation && pollId === polling && enabled.value) timer = setTimeout(poll, 2000)
  }
  async function enqueue(content: string, options: QueueOptions) {
    const sid = sessionId.value, version = generation
    if (!sid || !enabled.value || sending.value) return false
    const key = JSON.stringify({ sid, content, options })
    if (pending?.key !== key) pending = { key, id: createClientId() }
    const id = pending.id
    sending.value = true
    try {
      const item = await enqueueMessage(sid, id, content, options)
      if (generation !== version || disposed) return false
      pending = undefined
      // 直接展示服务端已接收的消息，不等待下一次轮询；丢弃提交前的旧快照。
      sequence++
      const current = snapshot.value ?? { state: 'IDLE', paused: false, runId: null, nodeId: null, messages: [] }
      snapshot.value = { ...current, messages: current.messages.some(m => m.id === item.id)
        ? current.messages.map(m => m.id === item.id ? item : m) : [...current.messages, item] }
      onChange(snapshot.value)
      void refresh()
      return true
    } finally { if (generation === version) sending.value = false }
  }
  watch([sessionId, enabled], () => {
    generation++; clearTimeout(timer); snapshot.value = null; error.value = ''; sending.value = false; pending = undefined
    void poll()
  }, { immediate: true })
  const visible = () => { if (!document.hidden) { clearTimeout(timer); void poll() } }
  document.addEventListener('visibilitychange', visible)
  onBeforeUnmount(() => { disposed = true; generation++; clearTimeout(timer); document.removeEventListener('visibilitychange', visible) })
  return { snapshot, error, sending, refresh, enqueue }
}
