<script setup lang="ts">
import MessageList from '@/components/chat/MessageList.vue'
import type { DisplayMessage, ChatArtifactVO } from '@/types'

defineProps<{
  messages: DisplayMessage[]
  sessionId?: string | null
  showThinking?: boolean
}>()

defineEmits<{ openArtifact: [artifact: ChatArtifactVO] }>()
</script>

<template>
  <MessageList :messages="messages" :session-id="sessionId" :show-thinking="showThinking" :tool-calls="[]" readonly-interaction
    @open-artifact="$emit('openArtifact', $event)" />
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;
</style>
