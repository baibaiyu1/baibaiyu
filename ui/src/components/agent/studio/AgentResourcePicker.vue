<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 资源选择弹窗
 *
 * 交互参考「青云Agent平台」的资源选择器：卡片头部的「添加」按钮打开弹窗，
 * 弹窗内是「搜索 + 双列资源卡（勾选框 / 名称 / 描述 / 标签）」，
 * 点选即切换选中态，确认后整表回写。
 *
 * 设计取舍：项目里没有等价的通用选择器，这里做成 Studio 专用的无状态受控组件——
 * 候选列表由调用方（各 AgentForm* 子表单）传入，弹窗只维护「草稿选中集」，
 * 因此取消关闭不会污染表单。
 *
 * @component
 */
import { computed, ref, watch } from 'vue'
import { CheckOutlined, PushpinOutlined, SearchOutlined } from '@ant-design/icons-vue'

export interface AgentPickerItem {
  /** 资源标识（与表单里存的 id 一致） */
  id: string
  /** 资源名称 */
  name: string
  /** 资源描述 */
  desc?: string
  /** 右侧标签，如协议 / 状态 / 类型 */
  badge?: string
  /** 是否禁止选择（如 MCP 运行时不可用） */
  disabled?: boolean
  /** 禁止选择的原因（展示在标签位） */
  disabledReason?: string
}

const props = withDefaults(defineProps<{
  /** 是否打开 */
  open: boolean
  /** 弹窗标题，如「添加工具」 */
  title: string
  /** 标题下的说明 */
  description?: string
  /** 候选项 */
  items: AgentPickerItem[]
  /** 已选 id */
  selectedIds: string[]
  /** 是否多选（false = 单选，点击即确认） */
  multiple?: boolean
  /** 搜索框占位文案 */
  searchPlaceholder?: string
  /** 空态文案 */
  emptyText?: string
}>(), {
  description: '',
  multiple: true,
  searchPlaceholder: '搜索名称 / 描述',
  emptyText: '暂无可选资源'
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 确认选择（单选时点选即触发） */
  confirm: [ids: string[]]
}>()

const keyword = ref('')
const draft = ref<string[]>([])

/**
 * 用表单当前值重置草稿（id 统一成字符串，避免候选列表用 String(id) 而表单里是数字导致勾不上）
 */
function resetDraft() {
  draft.value = (props.selectedIds || []).map(id => String(id))
  keyword.value = ''
}

/*
 * 调用方都用 `v-if="activePicker"` + `:open="true"` 渲染本弹窗：**每次打开都是全新实例**，
 * 且初始值就是 true——普通 watch 永远等不到 open 变化，草稿会一直是空数组，
 * 于是「已添加的工具 / MCP 再点添加时不勾选」。故必须 immediate 初始化。
 */
watch(() => props.open, (open) => {
  if (open) resetDraft()
}, { immediate: true })

// 候选项 / 已选项是异步到达的：打开状态下表单值变化时同步草稿，保证勾选态与服务端一致
watch(() => props.selectedIds, () => {
  if (props.open) resetDraft()
})

const filtered = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return props.items
  return props.items.filter(item =>
    `${item.name} ${item.desc || ''} ${item.badge || ''}`.toLowerCase().includes(query)
  )
})

function isSelected(id: string) {
  return draft.value.includes(String(id))
}

function toggle(item: AgentPickerItem) {
  const id = String(item.id)
  if (item.disabled && !isSelected(id)) return
  if (!props.multiple) {
    draft.value = [id]
    confirm()
    return
  }
  const index = draft.value.indexOf(id)
  if (index >= 0) draft.value.splice(index, 1)
  else draft.value.push(id)
}

function confirm() {
  emit('confirm', [...draft.value])
  emit('update:open', false)
}

function close() {
  emit('update:open', false)
}

function resetSearch() {
  keyword.value = ''
  resetDraft()
}
</script>

<template>
  <a-modal
    :open="open"
    :title="title"
    :width="880"
    :footer="null"
    :destroy-on-close="true"
    wrap-class-name="agstudio-picker-modal"
    @cancel="close"
  >
    <div class="agstudio-picker">
      <div class="agstudio-picker__head">
        <div class="agstudio-picker__intro">
          <p v-if="description">{{ description }}</p>
          <span>已选 <b>{{ draft.length }}</b> / {{ items.length }} 项</span>
        </div>
        <div class="agstudio-picker__tools">
          <a-input
            v-model:value="keyword"
            class="agstudio-picker__search"
            :placeholder="searchPlaceholder"
            allow-clear
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
          <a-button @click="resetSearch">重置</a-button>
        </div>
      </div>

      <div v-if="filtered.length" class="agstudio-picker__list">
        <button
          v-for="item in filtered"
          :key="item.id"
          type="button"
          class="agstudio-picker__item"
          :class="{ 'is-selected': isSelected(item.id), 'is-disabled': item.disabled && !isSelected(item.id) }"
          @click="toggle(item)"
        >
          <span class="agstudio-picker__check">
            <CheckOutlined v-if="isSelected(item.id)" />
          </span>
          <span class="agstudio-picker__copy">
            <strong :title="item.name">{{ item.name }}</strong>
            <small :title="item.desc">{{ item.desc || '暂无描述' }}</small>
          </span>
          <em v-if="item.disabled && !isSelected(item.id)" class="is-warn">
            {{ item.disabledReason || '不可用' }}
          </em>
          <em v-else-if="item.badge">{{ item.badge }}</em>
        </button>
      </div>

      <div v-else class="agstudio-picker__empty">
        <PushpinOutlined />
        <span>{{ keyword ? '没有找到匹配的资源' : emptyText }}</span>
      </div>

      <div class="agstudio-picker__footer">
        <span class="agstudio-picker__hint">
          {{ multiple ? '可多选，确认后整表回写到智能体配置' : '单选，点击即生效' }}
        </span>
        <a-button @click="close">取消</a-button>
        <a-button type="primary" @click="confirm">
          {{ multiple ? `确认（${draft.length}）` : '确定' }}
        </a-button>
      </div>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-picker.scss' as *;
</style>
