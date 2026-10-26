/**
 * 智能体卡片组件
 *
 */
<script setup lang="ts">
import ResourceOwnershipBadge from '@/components/resource/ResourceOwnershipBadge.vue'
import ResourceCardTag from '@/components/resource/ResourceCardTag.vue'
import { computed } from 'vue'
import { EllipsisOutlined, ClockCircleOutlined, StarFilled, StarOutlined } from '@ant-design/icons-vue'
import agentAvatar from '@/assets/avatar/agent.png'
import type { AgentDefinitionVO } from '@/types'
import { useAccountStore } from '@/stores'
import {
  createViewItem,
  createConfigPanelItem,
  createEnableItem,
  createDeleteItem,
  createGoVisitItem,
  createFavoriteItem,
  createForkItem,
  createVisibilityItem,
  createOwnershipItem,
  createGrantItem,
  createDivider,
} from '@/composables/useCardMenuItems'
import { isResourceManageable, isResourceVisibilityChangeable } from '@/utils/resourceVisibility'

const accountStore = useAccountStore()

/**
 * Props定义
 */
const props = defineProps<{
  data: AgentDefinitionVO
  /** 当前账号是否已收藏该智能体 */
  favorited?: boolean
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  view: [id: string]
  configPanel: [id: string]
  delete: [id: string]
  enable: [id: string]
  goVisit: [id: string]
  favorite: [id: string]
  fork: [id: string]
  visibility: [payload: { id: string; visibility: 'PRIVATE' | 'TENANT' }]
  grants: [id: string]
  ownership: [id: string]
}>()

/**
 * 格式化更新时间
 */
const formattedTime = computed(() => {
  if (!props.data.updatedAt) return ''
  const date = new Date(props.data.updatedAt)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
})

const agentTags = computed(() => props.data.tags?.length
  ? props.data.tags
  : props.data.tag ? [props.data.tag] : [])
const remainingTags = computed(() => agentTags.value.slice(2))

/**
 * 操作菜单项
 *
 * 维护与移交资格由后端按资产归属统一判定，无维护资格时仅展示只读与使用入口。
 */
const menuItems = computed(() => {
  if (accountStore.isReadOnly) {
    return [
      createViewItem(),
      createFavoriteItem(props.favorited === true),
      createDivider(),
      ...(props.data.canUse === false ? [] : [createGoVisitItem()]),
    ]
  }
  const manageable = isResourceManageable(props.data)
  const canChangeVisibility = isResourceVisibilityChangeable(props.data)
  return [
    createViewItem(),
    ...(manageable ? [createConfigPanelItem(), createEnableItem(props.data.enabled)] : []),
    createDivider(),
    createFavoriteItem(props.favorited === true),
    createForkItem(),
    ...(canChangeVisibility ? [createVisibilityItem(props.data.visibility)] : []),
    ...(manageable ? [createGrantItem()] : []),
    ...(props.data.canTransfer ? [createOwnershipItem()] : []),
    createDivider(),
    ...(props.data.canUse === false ? [] : [createGoVisitItem()]),
    ...(manageable ? [createDivider(), createDeleteItem()] : []),
  ]
})

/**
 * 点击标题
 */
function handleTitleClick() {
  if (!isResourceManageable(props.data) || accountStore.isReadOnly) {
    emit('view', String(props.data.id))
  } else {
    emit('configPanel', String(props.data.id))
  }
}

/**
 * 处理菜单点击
 */
function handleMenuClick({ key }: { key: string }) {
  const id = String(props.data.id)
  switch (key) {
    case 'view':
      emit('view', id)
      break
    case 'configPanel':
      emit('configPanel', id)
      break
    case 'enable':
      emit('enable', id)
      break
    case 'delete':
      emit('delete', id)
      break
    case 'goVisit':
      emit('goVisit', id)
      break
    case 'favorite':
      emit('favorite', id)
      break
    case 'fork':
      emit('fork', id)
      break
    case 'visibility':
      emit('visibility', {
        id,
        visibility: props.data.visibility === 'PRIVATE' ? 'TENANT' : 'PRIVATE'
      })
      break
    case 'ownership':
      emit('ownership', String(props.data.id))
      break
    case 'grants':
      emit('grants', id)
      break
  }
}
</script>

<template>
  <div class="agent-card">
    <div class="card-header flex items-center gap-sm">
      <div class="card-avatar-wrapper">
        <div class="card-avatar flex-center" :class="{ disabled: !data.enabled }">
          <img :src="agentAvatar" alt="agent" />
        </div>
        <span
          v-if="data?.jobInfo"
          class="avatar-corner-badge"
          :class="{ 'badge-active': data?.jobInfo?.enabled && data.enabled }"
        >
          <ClockCircleOutlined />
        </span>
      </div>
      <div class="card-heading">
        <div class="card-name truncate" :title="data.name" @click="handleTitleClick">{{ data.name }}</div>
        <ResourceOwnershipBadge :resource="data" />
      </div>
      <AButton
        type="text"
        size="small"
        class="favorite-button"
        :class="{ favorited }"
        :title="favorited ? '取消收藏' : '收藏'"
        @click.stop="emit('favorite', String(data.id))"
      >
        <StarFilled v-if="favorited" />
        <StarOutlined v-else />
      </AButton>
      <ADropdown :trigger="['hover']">
        <AButton type="text" size="small" title="更多操作">
          <EllipsisOutlined />
        </AButton>
        <template #overlay>
          <AMenu @click="handleMenuClick" :items="menuItems"></AMenu>
        </template>
      </ADropdown>
    </div>

    <div class="card-content line-clamp-3" :title="data.description">
      {{ data.description }}
    </div>

    <div class="card-footer flex items-center justify-between">
      <div class="card-tags flex items-center gap-xs">
        <ResourceCardTag>{{ data.agentType === 'CUSTOM' ? '自定义' : 'A2A' }}</ResourceCardTag>
        <ResourceCardTag v-for="tag in agentTags.slice(0, 2)" :key="tag" :title="tag">{{ tag }}</ResourceCardTag>
        <ATooltip v-if="remainingTags.length" :title="remainingTags.join('、')">
          <ResourceCardTag>+{{ remainingTags.length }}</ResourceCardTag>
        </ATooltip>
        <ResourceCardTag v-if="data.forkFromId">副本</ResourceCardTag>
        <ResourceCardTag v-if="data.studioConfigId" tone="accent">Studio</ResourceCardTag>
        <ResourceCardTag v-if="data.published === false" tone="warning">未发布</ResourceCardTag>
      </div>
      <div v-if="!data.studioConfigId" class="card-time text-placeholder text-xs">
        更新于 {{ formattedTime }}
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/resource-card-meta.scss';
.agent-card {
  min-height: 180px;
  padding: var(--spacing-md);
  background-color: #FFFFFF;
  border-radius: var(--border-radius-lg);
  border: 1px solid #ebebeb;
  transition: all var(--transition-base);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);

  &:hover {
    box-shadow: 0 4px 6px -5px rgba(0, 0, 0, 0.3);
    transform: translateY(-2px);
  }

  .card-header {
    .card-avatar-wrapper {
      position: relative;
      flex-shrink: 0;
    }

    .card-avatar {
      width: 40px;
      height: 40px;
      background-color: #e8eaf6;
      border-radius: var(--border-radius-xl);
      flex-shrink: 0;

      img {
        width: 28px;
        height: 28px;
        object-fit: contain;
      }
    }

    .avatar-corner-badge {
      position: absolute;
      bottom: -4px;
      right: -4px;
      width: 18px;
      height: 18px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 50%;
      box-shadow: 0 0 0 2px var(--color-bg-white);
      background: #fff;
      color: #8a8a8a;
      font-size: 12px;

      &.badge-active {
        color: #4449d0;
      }
    }
  }

  .card-name {
    font-size: var(--font-size-base);
    font-weight: 600;
    color: var(--color-text-primary);
    cursor: pointer;
    transition: color var(--transition-base);
    position: relative;
  }

  .favorite-button {
    flex-shrink: 0;
    color: #bfbfbf;

    &.favorited {
      color: #faad14;
    }
  }

  .card-content {
    font-size: var(--font-size-sm);
    color: var(--color-text-regular);
    line-height: 1.6;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
    overflow: hidden;
    text-overflow: ellipsis;
    word-break: break-all;
    min-height: 65px;
    max-height: 65px;
  }

  .card-footer {
    padding-top: var(--spacing-xs);

    .card-tags {
      flex-wrap: wrap;
    }

    .card-time {
      white-space: nowrap;
    }
  }

  .disabled {
    color: #757575 !important;
    background-color: #e7e7e7 !important;

    img {
      filter: grayscale(100%);
      opacity: 0.5;
    }
  }
}
</style>
