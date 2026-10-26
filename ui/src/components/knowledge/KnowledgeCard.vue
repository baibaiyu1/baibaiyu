/**
 * 知识库配置卡片组件
 *
 */
<script setup lang="ts">
import ResourceOwnershipBadge from '@/components/resource/ResourceOwnershipBadge.vue'
import ResourceCardTag from '@/components/resource/ResourceCardTag.vue'
import { computed } from 'vue'
import { EllipsisOutlined, FolderOpenOutlined } from '@ant-design/icons-vue'
import type { KnowledgeBaseConfigVO } from '@/types'
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
import knowledgeAvatar from '@/assets/avatar/knowledgebase.png'
import dify from '@/assets/brand/dify-color.png'
import ragflow from '@/assets/brand/ragflow.png'
import bailian from '@/assets/brand/bailian-color.png'

const accountStore = useAccountStore()

/**
 * Props定义
 */
const props = defineProps<{
  data: KnowledgeBaseConfigVO
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  view: [id: string]
  edit: [id: string]
  delete: [id: string]
  enable: [id: string]
  manageDocuments: [id: string]
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
 * 知识库类型显示文本
 */
const kbTypeText = computed(() => {
  const typeMap: Record<string, string> = {
    LOCAL: '本地',
    BAILIAN: '百炼',
    DIFY: 'Dify',
    RAGFLOW: 'RagFlow',
    LIGHTRAG: '知识图谱',
  }
  return typeMap[props.data.kbType] || props.data.kbType
})
const kbLog = computed(() => {
  const typeMap: Record<string, any> = {
    LOCAL: knowledgeAvatar,
    BAILIAN: bailian,
    DIFY: dify,
    RAGFLOW: ragflow,
    LIGHTRAG: knowledgeAvatar,
  }
  return typeMap[props.data.kbType]
})

/**
 * 操作菜单项
 *
 * 维护与移交资格由后端 `ResourceOwnershipSupport` 按资产归属统一判定。
 */
const menuItems = computed(() => {
  const items = [createViewItem()]
  if (!accountStore.isReadOnly && isResourceManageable(props.data)) {
    items.push(
      createEditItem(),
      createEnableItem(props.data.enabled),
      createVisibilityItem(props.data.visibility),
      createGrantItem(),
      ...(props.data.canTransfer ? [createOwnershipItem()] : []),
      createDivider(),
      createDeleteItem()
    )
  }

  if (!accountStore.isReadOnly && !isResourceManageable(props.data)
      && isResourceVisibilityChangeable(props.data)) {
    items.push(createVisibilityItem(props.data.visibility))
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
    emit('view', String(props.data.id))
  } else {
    emit('edit', props.data.id as string)
  }
}
</script>

<template>
  <div class="knowledge-card">
    <div class="card-header flex items-center gap-sm">
      <div class="card-avatar flex-center" :class="{ disabled: !data.enabled }"><img :src="kbLog" alt="knowledge" /></div>
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
        <ResourceCardTag>{{ kbTypeText }}</ResourceCardTag>
      </div>
      <div class="card-footer-right flex items-center gap-xs">
        <AButton
          v-if="data.kbType === 'LIGHTRAG' && !accountStore.isReadOnly && isResourceManageable(data)"
          v-permission="['knowledge:config:edit']"
          type="link" size="small"
          @click.stop="emit('manageDocuments', String(data.id))"
        ><FolderOpenOutlined />管理</AButton>
        <div class="card-time text-placeholder text-xs">更新于 {{ formattedTime }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/resource-card-meta.scss';
.knowledge-card {
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
