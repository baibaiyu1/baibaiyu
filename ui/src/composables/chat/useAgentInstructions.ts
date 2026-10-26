import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { getInstructions, submitInstruction, type InstructionInbox } from '@/api/agui/instructions'

/** 会话切换丢弃旧请求结果；提交重试复用请求标识，避免网络断开后重复接收。 */
export function useAgentInstructions(sessionId: Ref<string | null | undefined>, running: Ref<boolean>) {
  const inbox = ref<InstructionInbox | null>(null)
  const content = ref('')
  const error = ref('')
  const sending = ref(false)
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false
  let polling = 0
  let requestSequence = 0
  let pending: { runId: string; content: string; requestId: string } | undefined

  async function refresh() {
    const id = sessionId.value
    const version = generation
    const sequence = ++requestSequence
    if (!id || disposed) return
    try {
      const value = await getInstructions(id)
      if (!disposed && generation === version && sequence === requestSequence) {
        inbox.value = value
        error.value = ''
      }
    } catch (e) {
      if (!disposed && generation === version && sequence === requestSequence) {
        if (inbox.value) inbox.value.accepting = false
        error.value = e instanceof Error ? e.message : '无法刷新补充要求状态'
      }
    }
  }
  async function poll() {
    const version = generation
    const pollId = ++polling
    await refresh()
    if (!disposed && generation === version && polling === pollId && (running.value || inbox.value?.messages.some(m => ['RECEIVED', 'INJECTING'].includes(m.state))))
      timer = setTimeout(poll, 2000)
  }
  async function send() {
    const id = sessionId.value, runId = inbox.value?.runId, text = content.value.trim(), version = generation
    if (!id || !runId || !inbox.value?.accepting || !text || sending.value) return
    sending.value = true
    error.value = ''
    if (!pending || pending.runId !== runId || pending.content !== text)
      pending = { runId, content: text, requestId: Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('') }
    try {
      await submitInstruction(id, runId, pending.requestId, text)
      if (generation !== version || disposed) return
      content.value = ''
      pending = undefined
      await refresh()
    } catch (e) {
      if (generation === version && !disposed) error.value = e instanceof Error ? e.message : '发送失败，请重试'
    } finally {
      if (generation === version && !disposed) sending.value = false
    }
  }
  watch(sessionId, () => {
    generation++
    clearTimeout(timer)
    inbox.value = null; content.value = ''; error.value = ''; sending.value = false; pending = undefined
    void poll()
  }, { immediate: true })
  watch(running, () => { clearTimeout(timer); void poll() })
  const visible = () => { if (!document.hidden) { clearTimeout(timer); void poll() } }
  document.addEventListener('visibilitychange', visible)
  onBeforeUnmount(() => {
    disposed = true; generation++; clearTimeout(timer)
    document.removeEventListener('visibilitychange', visible)
  })
  return { inbox, content, error, sending, refresh, send }
}
