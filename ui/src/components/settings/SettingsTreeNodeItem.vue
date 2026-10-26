/**
 * 系统设置 - 树节点（`SettingsTreePanel` 的递归子节点）
 *
 * 行结构：外层 `div` 负责整行的悬浮/选中底色，内层按钮负责「选中节点」，
 * 右侧独立的图标按钮组是行内操作（新增下级 / 查看详情等）——
 * 不能把按钮嵌在按钮里，所以三者必须是兄弟节点。
 *
 * 样式统一由 `SettingsTreePanel` 以非 scoped 方式引入（见 `_settings-tree.scss`）。
 *
 */
<script setup lang="ts">
import { inject, ref } from 'vue'
import {
  ApartmentOutlined,
  CaretDownOutlined,
  CaretRightOutlined,
  FileOutlined
} from '@ant-design/icons-vue'
import type { SettingsTreeNode } from './treeTypes'
import { SETTINGS_TREE_CTX } from './treeTypes'

const props = defineProps<{
  node: SettingsTreeNode
  depth: number
}>()

const ctx = inject(SETTINGS_TREE_CTX)!

/** 本地展开状态（默认展开，便于一眼看清层级） */
const expanded = ref(true)

function toggle() {
  expanded.value = !expanded.value
}

function runAction(key: string) {
  ctx.runAction(key, props.node)
}
</script>

<template>
  <div
    class="settings-tree-node"
    :class="{ active: ctx.selected.value === node.id, 'has-actions': ctx.actions.value.length > 0 }"
  >
    <button
      class="settings-tree-node-main"
      :style="{ paddingLeft: `${8 + props.depth * 16}px` }"
      :title="node.name"
      @click="ctx.select(node.id)"
    >
      <span
        class="settings-tree-toggle"
        :class="{ placeholder: !node.children || node.children.length === 0 }"
        @click.stop="toggle()"
      >
        <CaretDownOutlined v-if="node.children?.length && expanded" />
        <CaretRightOutlined v-else-if="node.children?.length" />
        <FileOutlined v-else />
      </span>
      <ApartmentOutlined class="settings-tree-icon" />
      <span class="settings-tree-label">{{ node.name }}</span>
      <span v-if="node.badge" class="settings-tree-badge">{{ node.badge }}</span>
    </button>

    <span v-if="ctx.actions.value.length" class="settings-tree-node-actions">
      <AButton
        v-for="action in ctx.actions.value"
        :key="action.key"
        type="link"
        size="small"
        :title="action.title"
        :danger="action.danger"
        @click.stop="runAction(action.key)"
      >
        <template #icon><component :is="action.icon" /></template>
      </AButton>
    </span>
  </div>

  <template v-if="node.children?.length && expanded">
    <SettingsTreeNodeItem
      v-for="child in node.children"
      :key="child.id"
      :node="child"
      :depth="props.depth + 1"
    />
  </template>
</template>
