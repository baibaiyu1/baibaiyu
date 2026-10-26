<script lang="ts">
import { defineComponent, ref, computed, h, watch } from 'vue'
import { useRoute } from 'vue-router'
import { chatKeyToken } from '@/api/auth.ts'
import { getAgentIdByChatKey } from '@/api/agentChatKey.ts'
import { getShareSession, setShareSession } from '@/utils/auth'
import Chat from '@/views/Chat/index.vue'

export default defineComponent({
  name: 'ChatWrapper',
  emits: ['error'],
  async setup(_, { emit }) {
    const chatAgentId = ref<string>()
    const shareIdentity = ref<{ id: string; nickname: string }>()
    const loading = ref(false)
    const errorMessage = ref('')
    const route = useRoute()
    const chatKey = computed(() => (route.params.chatKey as string) || '')
    let requestId = 0

    async function loadShare(key: string) {
      const currentRequest = ++requestId
      chatAgentId.value = undefined
      shareIdentity.value = undefined
      loading.value = true
      errorMessage.value = ''
      try {
        let data = getShareSession(key)
        if (!data) {
          const response = await chatKeyToken(key)
          if (currentRequest !== requestId) return
          data = response.data.data
          if (!data) throw new Error('分享链接无效或已关闭')
          setShareSession(key, data)
        }
        const res = await getAgentIdByChatKey(key)
        if (currentRequest !== requestId) return
        if (!res.data.data) throw new Error('分享链接无效或已关闭')
        shareIdentity.value = {
          id: String(data.userDetail.id),
          nickname: data.userDetail.name || data.userDetail.username || '访客'
        }
        chatAgentId.value = res.data.data
      } catch (error) {
        if (currentRequest !== requestId) return
        errorMessage.value = '分享链接已失效或暂时无法访问'
        console.error('初始化分享会话失败:', error)
        emit('error', error)
      } finally {
        if (currentRequest === requestId) loading.value = false
      }
    }

    await loadShare(chatKey.value)
    watch(chatKey, key => { void loadShare(key) })

    return () => chatAgentId.value
      ? h(Chat, {
          chatAgentId: chatAgentId.value,
          showAccount: false,
          shareIdentity: shareIdentity.value
        })
      : h('main', { class: 'share-state' }, [
          h('h1', loading.value ? '正在连接智能体' : '分享链接不可用'),
          h('p', loading.value ? '请稍候' : errorMessage.value),
          !loading.value && h('button', { type: 'button', onClick: () => loadShare(chatKey.value) }, '重试')
        ])
  }
})
</script>

<style scoped>
.share-state {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  background: #f7f8fa;
  color: #20252b;
  text-align: center;
}

.share-state h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.share-state p {
  margin: 0;
  color: #68717c;
}

.share-state button {
  min-height: 36px;
  padding: 0 16px;
  border: 1px solid #c6cdd4;
  border-radius: 6px;
  background: #fff;
  color: #20252b;
  cursor: pointer;
}
</style>
