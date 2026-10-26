<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  CheckCircleOutlined,
  DownOutlined,
  LoadingOutlined
} from '@ant-design/icons-vue'
import MessageItem from './MessageItem.vue'
import ToolCallItem from './ToolCallItem.vue'
import SubAgentCard from './SubAgentCard.vue'
import ChatImages from './ChatImages.vue'
import KnowledgeCitations from './KnowledgeCitations.vue'
import ArtifactCard from './ArtifactCard.vue'
import WorkspaceFilePreview from '@/components/workspace/WorkspaceFilePreview.vue'
import { provideChatImagePreview } from '@/composables/chat/useChatImagePreview'
import { isHiddenToolResult } from '@/utils/chat/toolResult'
import { messageMedia, type MessageMedia } from '@/utils/chat/messageMedia'
import type { DisplayMessage, SubAgentRunVO } from '@/types'
import type { FlatFileItem } from "@/composables/chat/useWorkspaceFiles.ts";
import type { ChatArtifactVO } from '@/types/vo'
import type { InteractionSubmitPayload } from '@/components/markdown/uip/types'

const props = defineProps<{
  messages: DisplayMessage[]
  sessionId?: string | null
  agentHasResult?: boolean
  toolCalls: Array<{ id: string; name: string; args: string; result?: string; elapsed?: number, needConfirm?: boolean }>
  /** 运行中/停止中：禁用气泡内的分支切换与重新生成 */
  branchDisabled?: boolean
  isRunning?: boolean
  readonlyInteraction?: boolean
  showThinking?: boolean
}>()
const { visible: imagePreviewVisible, images: previewImages, currentIndex: previewIndex } = provideChatImagePreview()

defineEmits<{
  (e: 'toolContent', value: any): void
  (e: 'inputTagPreview', value: FlatFileItem): void
  (e: 'interactionSubmit', payload: InteractionSubmitPayload): void
  (e: 'uipRetry', uipCode: string): void
  (e: 'vepRetry', vepCode: string): void
  /** 切换回复分支 */
  (e: 'switchBranch', payload: { messageId: string; delta: number }): void
  /** 重新生成指定回复 */
  (e: 'regenerate', messageId: string): void
  /** 点击产物卡片：在右栏打开预览 */
  (e: 'openArtifact', artifact: ChatArtifactVO): void
}>()

/** 消息分组 */
interface MessageGroup {
  text: boolean
  key: string
  isStreaming: boolean
  messages: DisplayMessage[]
  media?: MessageMedia
}

const media = computed(() => messageMedia(props.messages, props.sessionId ?? null, props.isRunning))

/**
 * 将消息列表按规则分组：连续 ≥2 条 thinking/tool 且后接 assistant 时，
 * 聚合成一个“行为聚合”组；否则每条独立展示。
 * 流式末尾未闭合的 run 不会被聚合。
 */
const messageGroups = computed<MessageGroup[]>(() => {
  const groups: MessageGroup[] = []
  const messages_ = props.messages

  for (let i = 0; i < messages_.length; i++) {
    const msg: DisplayMessage = messages_[i]!
    const resources = media.value.get(msg.id)
    if (resources?.images.length || resources?.files.length || resources?.citations.length) {
      groups.push({ text: true, key: msg.id, isStreaming: msg.isStreaming || false,
        messages: [msg], media: resources })
    } else if (msg.isMemoryCompression) {
      groups.push({
        text: true,
        key: msg.id,
        isStreaming: false,
        messages: [msg],
      })
    } else if (msg.role === 'thinking' || msg.role === 'tool') {
      if (groups.length > 0) {
        const lastGroup = groups[groups.length - 1]!
        if (!lastGroup.text) {
          lastGroup.messages.push(msg)
          lastGroup.isStreaming = msg.isStreaming || false
        } else {
          groups.push({
            text: false,
            key: msg.id,
            isStreaming: msg.isStreaming || false,
            messages: [msg],
          })
        }
      } else {
        groups.push({
          text: false,
          key: msg.id,
          isStreaming: msg.isStreaming || false,
          messages: [msg],
        })
      }
    } else {
      groups.push({
        text: true,
        key: msg.id,
        isStreaming: false,
        messages: [msg],
      })
    }
  }

  return groups
})

interface MessageTurn {
  key: string
  groups: Array<MessageGroup & { index: number }>
  actionMessageId?: string
  branchMessage?: DisplayMessage
}

/** 同一轮的思考、工具和中间文本使用紧凑间距，操作只放在最终回复下方。 */
const messageTurns = computed<MessageTurn[]>(() => {
  const turns: MessageTurn[] = []
  messageGroups.value.forEach((group, index) => {
    const isUser = group.messages[0]?.role === 'user'
    const previous = turns[turns.length - 1]
    if (!previous || isUser || previous.groups[0]?.messages[0]?.role === 'user') {
      turns.push({ key: group.key, groups: [{ ...group, index }] })
    } else {
      previous.groups.push({ ...group, index })
    }
  })
  turns.forEach((turn, index) => {
    const branchMessage = turn.groups.flatMap(group => group.messages).find(msg => (msg.branchTotal ?? 0) > 1)
    turn.groups = turn.groups.map(group => ({ ...group, messages: group.messages.filter(msg => !isHiddenToolResult(msg)
      && (props.showThinking !== false || msg.role !== 'thinking' || msg.isMemoryCompression)) }))
      .filter(group => group.messages.length > 0 || group.media?.images.length || group.media?.files.length || group.media?.citations.length)
    if (props.readonlyInteraction || (index === turns.length - 1 && props.branchDisabled)) return
    const messages = turn.groups.flatMap(group => group.messages)
      .filter(msg => msg.role !== 'system' && !msg.isMemoryCompression)
    const last = messages[messages.length - 1]
    if (last?.role === 'assistant' && last.content.trim() && !last.isStreaming) {
      turn.actionMessageId = last.id
      // 重新生成的分叉可能发生在首条思考/工具消息，保留原分支的导航目标。
      turn.branchMessage = branchMessage
    }
  })
  return turns
})

/** 聚合组展开状态 */
const expandedMap = ref<Record<string, boolean>>({})

function isExpanded(key: string): boolean {
  return expandedMap.value[key]!
}

/** 提取分组首条消息（single 组保证有且只有一条） */
function firstMessage(group: MessageGroup): DisplayMessage {
  return group.messages[0]!
}

function toggleAggregate(key: string) {
  expandedMap.value = {
    ...expandedMap.value,
    [key]: !expandedMap.value[key],
  }
}

function isSubAgentMessage(message: DisplayMessage): boolean {
  return message.role === 'subagent'
}

function subAgentRunFor(message: DisplayMessage): SubAgentRunVO {
  if (message.subAgentRun) return message.subAgentRun
  let anchor: { invocationId?: string; agentTitle?: string; agentCode?: string } = {}
  try { anchor = JSON.parse(message.content || '{}') } catch { /* malformed legacy anchor */ }
  return {
    invocationId: anchor.invocationId || message.id,
    agentTitle: anchor.agentTitle,
    agentCode: anchor.agentCode,
    status: 'SUCCESS',
    events: [],
  }
}
</script>

<template>
  <div class="chat-main-messages">
    <div v-for="(turn, turnIndex) in messageTurns" :key="turn.key" class="chat-message-turn">
      <template v-for="group in turn.groups" :key="group.key">
        <!-- 行为聚合组 -->
        <div v-if="group.messages.length > 1" class="chat-aggregate-panel">
          <div class="chat-aggregate-header" @click="toggleAggregate(group.key)">
            <span class="chat-aggregate-icon">
              <LoadingOutlined v-if="group.isStreaming" spin />
              <CheckCircleOutlined v-else />
            </span>
            <span class="chat-aggregate-title">行为聚合 ({{ group.messages.length }})</span>
            <span class="chat-aggregate-arrow" :class="{ expanded: isExpanded(group.key) }">
              <DownOutlined />
            </span>
          </div>
          <div class="chat-aggregate-body" :class="{ 'is-expanded': isExpanded(group.key) }">
            <MessageItem
              v-for="msg in group.messages"
              :key="msg.id"
              :id="msg.id"
              :current-index="0"
              :total-messages="0"
              :role="msg.role"
              :content="msg.content"
              :images="media.get(msg.id)?.references"
              :defer-knowledge-images="media.get(msg.id)?.deferKnowledgeImages"
              :created-at="msg.createdAt"
              :agent-has-result="agentHasResult"
              :is-streaming="msg.isStreaming"
              :is-memory-compression="msg.isMemoryCompression"
              :branch-index="msg.branchIndex"
              :branch-total="msg.branchTotal"
              :can-regenerate="msg.canRegenerate"
              :branch-disabled="branchDisabled"
              :readonly-interaction="readonlyInteraction"
              @inputTagPreview="$emit('inputTagPreview', $event as FlatFileItem)"
              @interaction-submit="$emit('interactionSubmit', $event)"
              @uip-retry="$emit('uipRetry', $event)"
              @vep-retry="$emit('vepRetry', $event)"
              @switch-branch="$emit('switchBranch', { messageId: msg.id, delta: $event })"
              @regenerate="$emit('regenerate', msg.id)"
            />
          </div>
        </div>
        <!-- 单条消息 -->
        <SubAgentCard
          v-else-if="group.messages.length && isSubAgentMessage(firstMessage(group))"
          :show-thinking="showThinking"
          :key="`subagent-${firstMessage(group).id}`"
          :run="subAgentRunFor(firstMessage(group))"
          @inputTagPreview="$emit('inputTagPreview', $event as FlatFileItem)"
          @interaction-submit="$emit('interactionSubmit', $event)"
          @uip-retry="$emit('uipRetry', $event)"
          @vep-retry="$emit('vepRetry', $event)"
        />
        <MessageItem
          v-else-if="group.messages.length"
          :key="`message-${firstMessage(group).id}`"
          :id="firstMessage(group).id"
          :current-index="group.index"
          :total-messages="messageGroups.length"
          :role="firstMessage(group).role"
          :content="firstMessage(group).content"
          :images="media.get(firstMessage(group).id)?.references"
          :defer-knowledge-images="media.get(firstMessage(group).id)?.deferKnowledgeImages"
          :citations="media.get(firstMessage(group).id)?.citations"
          :citation-references="media.get(firstMessage(group).id)?.citationReferences"
          :created-at="firstMessage(group).createdAt"
          :agent-has-result="agentHasResult"
          :is-streaming="firstMessage(group).isStreaming"
          :is-memory-compression="firstMessage(group).isMemoryCompression"
          :show-reply-actions="firstMessage(group).id === turn.actionMessageId"
          :branch-index="turn.branchMessage?.branchIndex"
          :branch-total="turn.branchMessage?.branchTotal"
          :can-regenerate="firstMessage(group).canRegenerate"
          :branch-disabled="branchDisabled"
          :readonly-interaction="readonlyInteraction"
          @inputTagPreview="$emit('inputTagPreview', $event as FlatFileItem)"
          @interaction-submit="$emit('interactionSubmit', $event)"
          @uip-retry="$emit('uipRetry', $event)"
          @vep-retry="$emit('vepRetry', $event)"
          @switch-branch="$emit('switchBranch', { messageId: turn.branchMessage?.id ?? firstMessage(group).id, delta: $event })"
          @regenerate="$emit('regenerate', firstMessage(group).id)"
        />
        <ChatImages v-if="group.media?.images.length" :images="group.media.images" />
        <KnowledgeCitations v-if="group.media?.citations.length && firstMessage(group)?.role !== 'assistant'" :citations="group.media.citations" />
        <div v-if="group.media?.files.length" class="msg-artifacts" aria-label="此步骤生成的文件">
          <ArtifactCard v-for="artifact in group.media.files" :key="artifact.filePath" :artifact="artifact"
            :session-id="sessionId ?? String(artifact.sessionId)" @open="$emit('openArtifact', $event)" />
        </div>
      </template>
      <TransitionGroup v-if="turnIndex === messageTurns.length - 1" name="jelly">
        <ToolCallItem
          v-for="t in toolCalls"
          :key="t.id"
          :id="t.id"
          :name="t.name"
          :args="t.args"
          :result="t.result"
          :elapsed="t.elapsed"
          :loading="t.result == null"
          :need-confirm="t.needConfirm"
          @toolContent="(content: any) => $emit('toolContent', content)"
        />
      </TransitionGroup>
    </div>
    <WorkspaceFilePreview v-if="imagePreviewVisible" v-model:visible="imagePreviewVisible"
      v-model:current-index="previewIndex" :images="previewImages" :file-node="null" :session-id="null" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-message-turn {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.msg-artifacts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/** 微果冻动画：ToolCallItem 出现/消失 */
.jelly-enter-active {
  animation: jelly-enter 0.35s cubic-bezier(0.34, 1.3, 0.64, 1) both;
}

.jelly-leave-active {
  animation: jelly-leave 0.2s ease-out both;
}

.jelly-move {
  transition: transform 0.3s ease;
}

@keyframes jelly-enter {
  0% {
    opacity: 0;
    transform: translateY(3px);
  }
  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes jelly-leave {
  0% {
    opacity: 1;
    transform: scale(0.99);
  }
  100% {
    opacity: 0;
    transform: scale(0.98);
  }
}
</style>
