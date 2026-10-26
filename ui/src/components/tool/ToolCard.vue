/**
 * 工具配置卡片组件
 *
 */
<script setup lang="ts">
import ResourceOwnershipBadge from '@/components/resource/ResourceOwnershipBadge.vue'
import ResourceCardTag from '@/components/resource/ResourceCardTag.vue'
import { computed } from 'vue'
import { EllipsisOutlined } from '@ant-design/icons-vue'
import toolAvatar from '@/assets/avatar/tool.png'
import type { ToolVO } from '@/types'
import {
  createViewItem,
  createEditItem,
  createEnableItem,
  createDeleteItem,
  createVisibilityItem,
  createOwnershipItem,
  createGrantItem,
  createDivider,
} from '@/composables/useCardMenuItems'
import { useAccountStore } from '@/stores'
import { isResourceManageable, isResourceVisibilityChangeable } from '@/utils/resourceVisibility'

const accountStore = useAccountStore()

/**
 * Props定义
 */
const props = defineProps<{
  data: ToolVO
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  view: [id: string]
  edit: [id: string]
  delete: [id: string]
  enable: [id: string]
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

/**
 * 工具类型显示文本
 */
const toolTypeText = computed(() => {
  return props.data.toolType === 'BUILTIN' ? '内置' : '自定义'
})

/**
 * 操作菜单项
 *
 * 维护与移交资格由后端按资产归属统一判定。
 */
const menuItems = computed(() => {
  if (!isResourceManageable(props.data)) {
    return [
      createViewItem(),
      ...(isResourceVisibilityChangeable(props.data) ? [createVisibilityItem(props.data.visibility)] : [])
    ]
  }
  const items = [
    createViewItem(),
    createEditItem(),
    createEnableItem(props.data.enabled),
    createVisibilityItem(props.data.visibility),
    createGrantItem(),
    ...(props.data.canTransfer ? [createOwnershipItem()] : []),
  ]
  if (props.data.toolType !== 'BUILTIN') {
    items.push(createDivider())
    items.push(createDeleteItem())
  }
  return items
})

/**
 * 处理菜单点击
 */
function handleMenuClick({ key }: { key: string }) {
  switch (key) {
    case 'view':
      emit('view', props.data.id as string)
      break
    case 'edit':
      emit('edit', props.data.id as string)
      break
    case 'enable':
      emit('enable', props.data.id as string)
      break
    case 'delete':
      emit('delete', props.data.id as string)
      break
    case 'visibility':
      emit('visibility', {
        id: String(props.data.id),
        visibility: props.data.visibility === 'PRIVATE' ? 'TENANT' : 'PRIVATE'
      })
      break
    case 'ownership':
      emit('ownership', String(props.data.id))
      break
    case 'grants':
      emit('grants', String(props.data.id))
      break
  }
}

/**
 * 点击标题
 */
function handleTitleClick() {
  if (accountStore.isReadOnly || !isResourceManageable(props.data)) {
    emit('view', props.data.id as string)
  } else {
    emit('edit', props.data.id as string)
  }
}
</script>

<template>
  <div class="tool-card">
    <div class="card-header flex items-center gap-sm">
      <div class="card-avatar flex-center" :class="{ disabled: !data.enabled }"><img :src="toolAvatar" alt="tool" /></div>
      <div class="card-heading">
        <div class="card-name truncate" :title="data.name" @click="handleTitleClick">{{ data.name }}</div>
        <ResourceOwnershipBadge :resource="data" />
      </div>
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
        <ResourceCardTag>{{ toolTypeText }}</ResourceCardTag>
        <ResourceCardTag v-if="data.category" :title="data.category">{{ data.category }}</ResourceCardTag>
      </div>
      <div class="card-time text-placeholder text-xs">{{ formattedTime }}</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/resource-card-meta.scss';
.tool-card {
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

    .card-name {
      font-size: var(--font-size-base);
      font-weight: 600;
      color: var(--color-text-primary);
      cursor: pointer;
      transition: color var(--transition-base);

      //&:hover {
      //  color: #42a5f5;
      //}
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
