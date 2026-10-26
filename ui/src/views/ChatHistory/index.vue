<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Modal } from 'ant-design-vue'
import { useAgentDetail } from '@/composables/chat/useAgentDetail'
import { useSessions } from '@/composables/chat/useSessions'
import { useCurrentSession } from '@/composables/chat/useCurrentSession'
import { useChatArtifacts } from '@/composables/chat/useChatArtifacts'
import { attachArtifacts } from '@/utils/chat/artifact'
import ChatSidebar from '@/components/chatHistory/ChatSidebar.vue'
import ChatMain from '@/components/chatHistory/ChatMain.vue'
import type { DisplayMessage } from '@/types'

const route = useRoute()

const agentId = computed(() => (route.params.agentId as string) || '')

// 智能体详情
const { agentDetail } = useAgentDetail(agentId)

// 会话列表管理
const {
  sessions,
  loading: sessionsLoading,
  hasMore: sessionsHasMore,
  deleteSessionForAll,
  loadMoreAllSessions,
  resetAndReloadAll,
} = useSessions(agentId)

// 当前会话管理
const {
  currentSessionId,
  currentSessionTitle,
  messagesList,
  selectSession,
  resetSession,
} = useCurrentSession(agentId)
const { artifacts } = useChatArtifacts(currentSessionId)

// 构建展示消息
const displayMessages = computed<DisplayMessage[]>(() => {
  const list: DisplayMessage[] = []
  for (const m of messagesList.value) {
    if (m.role === 'system') continue
    list.push({
      id: String(m.id),
      role: m.role as any,
      content: (m.content || '') as string,
      isStreaming: false,
      createdAt: m.createdAt,
      subAgentRun: m.subAgentRun ?? null,
    })
  }
  return attachArtifacts(list, artifacts.value)
})

// 选择会话
const handleSelectSession = async (session: any) => {
  await selectSession(session)
}

// 会话菜单操作
const handleDeleteSession = async (session: any) => {
  const id = String(session.id)
  Modal.confirm({
    title: '确认删除',
    content: '删除后无法恢复，是否继续？',
    onOk: async () => {
      await deleteSessionForAll(id)
      if (currentSessionId.value === id) {
        resetSession()
      }
    },
  })
}


// 初始化加载会话列表
onMounted(() => {
  resetAndReloadAll()
})
</script>

<template>
  <div class="chat-page">
    <ChatSidebar
      :agent-name="agentDetail?.name"
      :sessions="sessions"
      :current-session-id="currentSessionId"
      :loading="sessionsLoading"
      :has-more="sessionsHasMore"
      @select-session="handleSelectSession"
      @delete-session="handleDeleteSession"
      @load-more="loadMoreAllSessions"
    />

    <ChatMain
      ref="chatMainRef"
      :title="currentSessionTitle || agentDetail?.name || '对话'"
      :messages="displayMessages"
      :session-id="currentSessionId"
      :show-thinking="agentDetail?.reasoningConfig?.showThinking !== false"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;
</style>
