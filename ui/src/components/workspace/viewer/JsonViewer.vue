<script setup lang="ts">
/**
 * JSON 预览渲染器
 *
 * 折叠树。仓内没有现成的 JSON 树组件（已核实：无 vue-json-viewer 等依赖），故自行实现。
 * 拍平与可见性过滤逻辑放在 ./jsonTree.ts，便于单测。
 */
import { computed, ref } from 'vue'
import { DownOutlined, RightOutlined } from '@ant-design/icons-vue'
import { buildJsonRows, visibleJsonRows } from './jsonTree'

const props = defineProps<{
  /** JSON 源文本 */
  content: string
}>()

/** 解析 + 拍平结果 */
const built = computed(() => buildJsonRows(props.content ?? ''))

/** 全部行 */
const allRows = computed(() => built.value.rows)

/** 已折叠的路径集合（默认全展开） */
const collapsed = ref<Set<string>>(new Set())

/**
 * 切换节点折叠状态
 *
 * @param path 节点路径
 */
function toggle(path: string) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

/** 可见行 */
const visibleRows = computed(() => visibleJsonRows(allRows.value, collapsed.value))

/**
 * 全部展开 / 全部收起
 */
function toggleAll() {
  collapsed.value = collapsed.value.size > 0
    ? new Set()
    : new Set(allRows.value.filter((r) => r.expandable).map((r) => r.path))
}
</script>

<template>
  <div class="json-viewer">
    <!-- 解析失败：展示原始文本并给出错误，避免预览白屏 -->
    <template v-if="!built.ok">
      <div class="json-viewer-error">JSON 解析失败：{{ built.message }}</div>
      <pre class="json-viewer-raw">{{ content }}</pre>
    </template>

    <template v-else>
      <div class="json-viewer-bar">
        <span class="json-viewer-meta">{{ allRows.length }} 个节点</span>
        <span class="json-viewer-spacer" />
        <button class="json-viewer-btn" @click="toggleAll">
          {{ collapsed.size > 0 ? '全部展开' : '全部收起' }}
        </button>
      </div>

      <div class="json-viewer-body">
        <div
          v-for="row in visibleRows"
          :key="row.path"
          class="json-viewer-row"
          :style="{ paddingLeft: `${row.depth * 14 + 8}px` }"
        >
          <span
            class="json-viewer-caret"
            :class="{ 'is-leaf': !row.expandable }"
            @click="row.expandable && toggle(row.path)"
          >
            <DownOutlined v-if="row.expandable && !collapsed.has(row.path)" />
            <RightOutlined v-else-if="row.expandable" />
          </span>

          <span v-if="!row.isIndex" class="json-viewer-key">"{{ row.key }}"</span>
          <span v-else class="json-viewer-index">{{ row.key }}</span>
          <span class="json-viewer-colon">:</span>

          <span v-if="row.expandable" class="json-viewer-summary">{{ row.summary }}</span>
          <span v-else class="json-viewer-value" :class="`is-${row.type}`">{{ row.value }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.json-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.json-viewer-bar {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  height: 34px;
  padding: 0 10px;
  border-bottom: 1px solid #ededed;
  font-size: 12px;
  color: #8c8c8c;
}

.json-viewer-spacer {
  flex: 1;
}

.json-viewer-btn {
  padding: 2px 8px;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  background: transparent;
  font-size: 12px;
  color: #8c8c8c;
  cursor: pointer;

  &:hover {
    color: #0f74ff;
    border-color: #0f74ff;
  }
}

.json-viewer-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px 0;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}

.json-viewer-row {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.json-viewer-caret {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  flex-shrink: 0;
  font-size: 10px;
  color: #8c8c8c;
  cursor: pointer;

  &.is-leaf {
    cursor: default;
  }
}

.json-viewer-key {
  color: #0f74ff;
}

.json-viewer-index {
  color: #bfbfbf;
}

.json-viewer-colon {
  color: #8c8c8c;
}

.json-viewer-summary {
  color: #bfbfbf;
}

// 标量值按类型着色，与常见 JSON 查看器一致
.json-viewer-value {
  &.is-string {
    color: #389e0d;
  }

  &.is-number {
    color: #d46b08;
  }

  &.is-boolean {
    color: #722ed1;
  }

  &.is-null,
  &.is-undefined {
    color: #bfbfbf;
    font-style: italic;
  }
}

.json-viewer-error {
  padding: 10px 12px;
  background: #fff2f0;
  border-bottom: 1px solid #ffccc7;
  font-size: 12px;
  color: #cf1322;
}

.json-viewer-raw {
  flex: 1;
  min-height: 0;
  overflow: auto;
  margin: 0;
  padding: 12px;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
