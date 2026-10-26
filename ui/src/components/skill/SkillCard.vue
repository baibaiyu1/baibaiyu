/**
 * 技能包卡片组件
 *
 */
<script setup lang="ts">
import ResourceOwnershipBadge from '@/components/resource/ResourceOwnershipBadge.vue'
import ResourceCardTag from '@/components/resource/ResourceCardTag.vue'
import { ref, computed, defineComponent } from 'vue'
import { EllipsisOutlined, PlusOutlined, ToolOutlined } from '@ant-design/icons-vue'
import skillAvatar from '@/assets/avatar/skill.png'
import { message } from 'ant-design-vue'
import type { SkillPackageVO } from '@/types'
import * as skillApi from '@/api/skill'
import {
  createViewItem,
  createEditItem,
  createEnableItem,
  createDeleteItem,
  createSetCategoryItem,
  createEditAliasItem,
  createToolLinkItem,
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
  data: SkillPackageVO
  categories: string[]
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  view: [id: string]
  edit: [id: string]
  delete: [id: string]
  enable: [id: string]
  setCategory: [id: string, category: string]
  setAlias: [id: string]
  toolLink: [id: string]
  visibility: [payload: { id: string; visibility: 'PRIVATE' | 'TENANT' }]
  grants: [id: string]
  ownership: [id: string]
}>()

/**
 * 展示名称：优先别名，别名不存在（未定义、为空、空字符）时使用技能名称
 */
const displayName = computed(() => props.data.alias?.trim() || props.data.name)

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
 * 操作菜单项
 *
 * 只有归属人或管理员能改 / 删 / 换分类 / 改别名 / 切换可见性；别人的技能只保留查看入口。
 */
const menuItems = computed(() => {
  if (!isResourceManageable(props.data)) {
    return [
      createViewItem(),
      ...(isResourceVisibilityChangeable(props.data) ? [createVisibilityItem(props.data.visibility)] : [])
    ]
  }
  return [
    createViewItem(),
    createEditItem(),
    createSetCategoryItem(),
    createEditAliasItem(),
    createToolLinkItem(),
    createEnableItem(props.data.enabled),
    createVisibilityItem(props.data.visibility),
    createGrantItem(),
    ...(props.data.canTransfer ? [createOwnershipItem()] : []),
    createDivider(),
    createDeleteItem(),
  ]
})

// 分类设置弹窗
const categoryModalVisible = ref(false)
const categoryValue = ref('')
const categorySearchText = ref('')
const categoryNewName = ref('')
// 别名设置弹窗
const aliasModalVisible = ref(false)
const aliasValue = ref('')
// 本地分类列表副本，支持运行时新增
const localCategories = ref<string[]>([...props.categories])

const filteredCategories = computed(() => {
  if (!categorySearchText.value) {
    return localCategories.value
  }
  const searchLower = categorySearchText.value.toLowerCase()
  const filtered = localCategories.value.filter(cat =>
    cat.toLowerCase().includes(searchLower)
  )
  if (!filtered.includes(categorySearchText.value)) {
    filtered.unshift(categorySearchText.value)
  }
  return filtered
})

const VNodes = defineComponent({
  props: {
    vnodes: { type: Object, required: true },
  },
  render() {
    return this.vnodes
  },
})

function addCategory(e: Event) {
  e.preventDefault()
  if (!categoryNewName.value) return
  if (!localCategories.value.includes(categoryNewName.value)) {
    localCategories.value.push(categoryNewName.value)
  }
  categoryValue.value = categoryNewName.value
  categoryNewName.value = ''
}

function openCategoryModal() {
  categoryValue.value = props.data.category || ''
  categorySearchText.value = ''
  categoryNewName.value = ''
  categoryModalVisible.value = true
}

async function handleCategoryConfirm() {
  if (!categoryValue.value.trim()) {
    message.warning('请选择或输入分类')
    return
  }
  try {
    const detailRes = await skillApi.detail(String(props.data.id))
    const vo = detailRes.data.data as SkillPackageVO
    if (vo) {
      await skillApi.update({
        id: String(vo.id),
        name: vo.name,
        description: vo.description,
        category: categoryValue.value,
        skillContent: '',
        references: null,
        examples: null,
        scripts: null,
        tools: vo.tools || [],
      })
      message.success('分类设置成功')
      categoryModalVisible.value = false
      emit('setCategory', String(props.data.id), categoryValue.value)
    }
  } catch {
    message.error('设置分类失败')
  }
}

/**
 * 打开编辑别名弹窗
 */
function openAliasModal() {
  aliasValue.value = props.data.alias || ''
  aliasModalVisible.value = true
}

async function handleAliasConfirm() {
  try {
    await skillApi.updateAlias(String(props.data.id), aliasValue.value.trim())
    message.success('别名设置成功')
    aliasModalVisible.value = false
    emit('setAlias', String(props.data.id))
  } catch {
    message.error('设置别名失败')
  }
}

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
    case 'setCategory':
      openCategoryModal()
      break
    case 'editAlias':
      openAliasModal()
      break
    case 'toolLink':
      emit('toolLink', props.data.id as string)
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
  <div class="skill-card">
    <div class="card-header flex items-center gap-sm">
      <ATooltip :title="data.tools && data.tools.length > 0 ? `已关联 ${data.tools.length} 个工具` : ''">
        <div class="card-avatar-wrapper">
          <div class="card-avatar flex-center" :class="{ disabled: !data.enabled }"><img :src="skillAvatar" alt="skill" /></div>
          <span
            v-if="data.tools && data.tools.length > 0"
            class="avatar-corner-badge"
          >
            <ToolOutlined />
          </span>
        </div>
      </ATooltip>
      <div class="card-heading">
        <div class="card-name truncate" :title="displayName" @click="handleTitleClick">{{ displayName }}</div>
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
        <ResourceCardTag v-if="data.category" :title="data.category">{{ data.category }}</ResourceCardTag>
      </div>
      <div class="card-time text-placeholder text-xs">更新于 {{ formattedTime }}</div>
    </div>
  </div>

  <!-- 分类设置弹窗 -->
  <a-modal
    v-model:open="categoryModalVisible"
    title="设置标签"
    :ok-text="'确定'"
    :cancel-text="'取消'"
    @ok="handleCategoryConfirm"
    destroyOnClose
  >
    <a-form layout="vertical">
      <a-form-item label="选择标签">
        <a-select
          v-model:value="categoryValue"
          placeholder="选择或输入标签"
          show-search
          @search="categorySearchText = $event"
        >
          <a-select-option v-for="cat in filteredCategories" :key="cat" :value="cat">
            {{ cat }}
          </a-select-option>
          <template #dropdownRender="{ menuNode: menu }">
            <VNodes :vnodes="menu" />
            <a-divider style="margin: 4px 0" />
            <a-space style="padding: 4px 8px">
              <a-input v-model:value="categoryNewName" style="width: 260px" placeholder="请输入新标签" />
              <a-button type="text" @click="addCategory">
                <template #icon>
                  <PlusOutlined />
                </template>
                添加
              </a-button>
            </a-space>
          </template>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>

  <!-- 编辑别名弹窗 -->
  <a-modal
    v-model:open="aliasModalVisible"
    title="编辑别名"
    :ok-text="'确定'"
    :cancel-text="'取消'"
    @ok="handleAliasConfirm"
    destroyOnClose
  >
    <a-form layout="vertical">
      <a-form-item label="技能别名">
        <a-input
          v-model:value="aliasValue"
          placeholder="请输入技能别名，留空则显示技能名称"
          :maxlength="200"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/resource-card-meta.scss';
.skill-card {
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
      color: #ab47bc;
      font-size: 11px;
    }

    .card-name {
      font-size: var(--font-size-base);
      font-weight: 600;
      color: var(--color-text-primary);
      cursor: pointer;
      transition: color var(--transition-base);

      //&:hover {
      //  color: #ab47bc;
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
