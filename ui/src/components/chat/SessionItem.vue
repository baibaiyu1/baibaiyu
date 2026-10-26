<script setup lang="ts">
import { EllipsisOutlined } from '@ant-design/icons-vue'
import type { ChatSessionVO } from '@/types'

interface MenuClickEvent {
  key: string
}

defineProps<{
  session: ChatSessionVO
  active: boolean
  isRunning?: boolean
}>()

const emit = defineEmits<{
  (e: 'click'): void
  (e: 'menu', key: string): void
}>()
</script>

<template>
  <div class="chat-history-item" :class="{ active, running: isRunning }" @click="emit('click')">
    <span class="chat-history-item-text" :title="session.title || '新对话'">
      {{ session.title || '新对话' }}
    </span>
    <span v-if="isRunning" class="chat-history-item-running-dot" />
    <ADropdown v-else :trigger="['click']">
      <AButton type="text" size="small" class="chat-history-item-more" @click.stop>
        <EllipsisOutlined />
      </AButton>
      <template #overlay>
        <AMenu @click="({ key }:MenuClickEvent) => emit('menu', key as string)">
          <AMenuItem key="rename">重命名</AMenuItem>
          <AMenuItem v-if="session.title !== '新对话'" :key="session.isPinned ? 'unpin' : 'pin'">
            {{ session.isPinned ? '取消置顶' : '置顶' }}
          </AMenuItem>
          <AMenuItem key="delete" danger>删除</AMenuItem>
        </AMenu>
      </template>
    </ADropdown>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-history-item-running-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #52c41a;
  margin: 8px;
  flex-shrink: 0;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}
</style>
