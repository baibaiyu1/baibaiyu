/**
 * 系统设置 - 树形导航面板（部门树 / 菜单树共用）
 *
 * 除「选中节点」外，还可通过 `actions` 为每个节点配置行内图标按钮
 * （如组织树的新增下级 / 查看详情），点击后以 `@action` 回传操作标识与节点。
 *
 * 样式以非 scoped 方式引入，同时覆盖递归子节点 `SettingsTreeNodeItem`。
 *
 */
<script setup lang="ts">
import { computed, provide, ref, toRef } from 'vue'
import { ReloadOutlined, SearchOutlined, UnorderedListOutlined } from '@ant-design/icons-vue'
import SettingsTreeNodeItem from './SettingsTreeNodeItem.vue'
import type { SettingsTreeAction, SettingsTreeContext, SettingsTreeNode } from './treeTypes'
import { SETTINGS_TREE_CTX } from './treeTypes'
// 面板骨架与递归子节点共用同一份全局样式（scoped 无法跨组件命中递归子节点）
import '@/styles/components/_settings-tree.scss'

const props = withDefaults(
  defineProps<{
    /** 树标题 */
    title?: string
    /** 树数据 */
    nodes?: SettingsTreeNode[]
    /** 当前选中节点ID（null 表示「全部」） */
    selected?: string | number | null
    /** 是否展示「全部」根节点 */
    showAll?: boolean
    /** 「全部」节点的文案 */
    allLabel?: string
    /** 是否展示搜索框 */
    searchable?: boolean
    /** 是否展示刷新按钮 */
    refreshable?: boolean
    /** 加载中 */
    loading?: boolean
    /** 每个节点的行内操作按钮（为空表示不展示操作） */
    actions?: SettingsTreeAction[]
  }>(),
  {
    title: '',
    nodes: () => [],
    selected: null,
    showAll: true,
    allLabel: '全部',
    searchable: true,
    refreshable: true,
    loading: false,
    actions: () => []
  }
)

const emit = defineEmits<{
  (e: 'select', id: string | number | null): void
  (e: 'refresh'): void
  (e: 'action', key: string, node: SettingsTreeNode): void
}>()

/** 搜索关键字 */
const keyword = ref('')

/** 按关键字剪枝：命中节点保留其全部祖先，保证层级完整 */
const visibleNodes = computed<SettingsTreeNode[]>(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) {
    return props.nodes
  }
  const filter = (list: SettingsTreeNode[]): SettingsTreeNode[] =>
    list.reduce<SettingsTreeNode[]>((acc, node) => {
      const children = node.children ? filter(node.children) : []
      if (node.name.toLowerCase().includes(query) || children.length > 0) {
        acc.push({ ...node, children })
      }
      return acc
    }, [])
  return filter(props.nodes)
})

/** 供递归子节点使用的上下文 */
provide<SettingsTreeContext>(SETTINGS_TREE_CTX, {
  selected: toRef(props, 'selected'),
  select: (id) => emit('select', id),
  actions: toRef(props, 'actions'),
  runAction: (key, node) => emit('action', key, node)
})
</script>

<template>
  <aside class="settings-tree-panel">
    <header class="settings-tree-header">
      <span class="settings-tree-title">{{ title }}</span>
      <span class="settings-tree-header-actions">
        <slot name="header-actions" />
        <AButton v-if="refreshable" type="text" size="small" title="刷新" @click="emit('refresh')">
          <template #icon><ReloadOutlined /></template>
        </AButton>
      </span>
    </header>

    <div v-if="searchable" class="settings-tree-search">
      <AInput v-model:value="keyword" size="small" allow-clear placeholder="请输入名称">
        <template #prefix><SearchOutlined /></template>
      </AInput>
    </div>

    <div class="settings-tree-body">
      <AppSpin :spinning="loading">
        <div v-if="showAll" class="settings-tree-node" :class="{ active: selected === null }">
          <button
            class="settings-tree-node-main"
            style="padding-left: 8px"
            :title="allLabel"
            @click="emit('select', null)"
          >
            <span class="settings-tree-toggle placeholder"><UnorderedListOutlined /></span>
            <span class="settings-tree-label">{{ allLabel }}</span>
          </button>
        </div>

        <SettingsTreeNodeItem v-for="node in visibleNodes" :key="node.id" :node="node" :depth="0" />

        <p v-if="visibleNodes.length === 0 && !loading" class="settings-tree-empty">暂无数据</p>
      </AppSpin>
    </div>
  </aside>
</template>
