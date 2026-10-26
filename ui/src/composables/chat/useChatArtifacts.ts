import { onMounted, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import * as chatSessionApi from '@/api/chatSession'
import { eventBus } from '@/websocket/core/event-bus'
import { WS_MESSAGE_TYPES } from '@/websocket/const/websocket'
import type { ChatArtifactVO } from '@/types'

/** 文件通知用于即时展示，接口补查用于结束、后台恢复和重连后的完整恢复。 */
export function useChatArtifacts(sessionId: Ref<string | null>, running?: Ref<boolean>) {
  const artifacts = ref<ChatArtifactVO[]>([])
  let version = 0
  let disposed = false
  let active: { version: number; repeat: boolean; promise: Promise<void> } | null = null
  const subscriptions: Array<() => void> = []

  function upsert(artifact: ChatArtifactVO) {
    if (String(artifact.sessionId) !== sessionId.value || !artifact.filePath) return
    const index = artifacts.value.findIndex(item => item.filePath === artifact.filePath)
    if (index < 0) {
      artifacts.value = [...artifacts.value, artifact]
      return
    }
    // 补查在途时可能已收到新版本通知；旧快照不能覆盖新名称、大小或所属轮次。
    if (new Date(artifact.createTime).getTime() < new Date(artifacts.value[index]!.createTime).getTime()) return
    const next = [...artifacts.value]
    next[index] = artifact
    artifacts.value = next
  }

  function refresh(): Promise<void> {
    const sid = sessionId.value
    if (disposed || !sid || !/^\d+$/.test(sid)) return Promise.resolve()
    if (active?.version === version) {
      active.repeat = true
      return active.promise
    }
    const request = { version, repeat: false, promise: Promise.resolve() }
    active = request
    request.promise = (async () => {
      do {
        request.repeat = false
        try {
          const res = await chatSessionApi.getArtifacts(sid)
          if (disposed || request.version !== version || sessionId.value !== sid) return
          for (const artifact of res.data.data ?? []) upsert(artifact)
        } catch {
          // 短暂断网保留已经展示的文件，下次结束/返回页面/重连再补查。
        }
      } while (request.repeat && !disposed && request.version === version)
    })().finally(() => { if (active === request) active = null })
    return request.promise
  }

  watch(sessionId, () => {
    version++
    active = null
    artifacts.value = []
    void refresh()
  }, { immediate: true, flush: 'sync' })

  if (running) watch(running, (value, previous) => {
    if (previous && !value) void refresh()
  })

  const recover = () => { void refresh() }
  const onVisible = () => { if (document.visibilityState === 'visible') recover() }
  onMounted(() => {
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('online', recover)
    window.addEventListener('pageshow', recover)
    subscriptions.push(eventBus.on('WEBSOCKET:CONNECTED', recover))
    subscriptions.push(eventBus.on(WS_MESSAGE_TYPES.ARTIFACT_CREATED, (payload: ChatArtifactVO) => {
      if (!payload || String(payload.sessionId) !== sessionId.value) return
      upsert({ ...payload, id: payload.id ?? `ws-${payload.filePath}`,
        sessionId: String(payload.sessionId), createTime: payload.createTime ?? new Date().toISOString() })
    }))
  })
  onBeforeUnmount(() => {
    disposed = true
    version++
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('online', recover)
    window.removeEventListener('pageshow', recover)
    subscriptions.forEach(unsubscribe => unsubscribe())
  })
  return { artifacts, refresh }
}
