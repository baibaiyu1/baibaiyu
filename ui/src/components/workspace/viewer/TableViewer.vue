<script setup lang="ts">
/**
 * CSV / TSV 表格预览渲染器
 *
 * 仓内没有任何 CSV 解析库（已核实：无 papaparse / xlsx），故自行实现。
 * 解析必须处理三种真实存在的写法，不能简单 split(',')：
 *   1. 引号包裹的字段（字段内可含分隔符）
 *   2. 字段内的 "" 转义为单个引号
 *   3. 引号字段内跨行（一个逻辑行占多物理行）
 *
 * 大文件保护：只渲染前 MAX_ROWS 行，超出部分给出提示。
 * 否则一个几万行的 CSV 会把右栏直接卡死。
 */
import { computed, ref } from 'vue'
import { parseDelimited } from './csv'

const props = defineProps<{
  /** 文件全文 */
  content: string
  /** 文件扩展名，用于判定分隔符 */
  extension?: string
}>()

/** 单次渲染的最大数据行数 */
const MAX_ROWS = 500

/** 首行是否作为表头 */
const firstRowIsHeader = ref(true)

/** 分隔符：tsv 用制表符，其余按逗号 */
const delimiter = computed(() => (props.extension === 'tsv' ? '\t' : ','))

/** 全部解析结果 */
const allRows = computed(() => parseDelimited(props.content ?? '', delimiter.value))

/** 表头 */
const header = computed<string[] | null>(() =>
  firstRowIsHeader.value && allRows.value.length > 0 ? (allRows.value[0] ?? null) : null
)

/** 数据行（受 MAX_ROWS 限制） */
const bodyRows = computed(() => {
  const rows = firstRowIsHeader.value ? allRows.value.slice(1) : allRows.value
  return rows.slice(0, MAX_ROWS)
})

/** 是否因超限被截断 */
const truncated = computed(() => {
  const rows = firstRowIsHeader.value ? allRows.value.slice(1) : allRows.value
  return rows.length > MAX_ROWS
})

/** 总列数（按最宽的行算，容忍不规则 CSV） */
const columnCount = computed(() =>
  allRows.value.reduce((max, r) => Math.max(max, r.length), 0)
)
</script>

<template>
  <div class="table-viewer">
    <div class="table-viewer-bar">
      <label class="table-viewer-check">
        <input v-model="firstRowIsHeader" type="checkbox" />
        <span>首行为表头</span>
      </label>
      <span class="table-viewer-meta">
        {{ bodyRows.length }} 行 × {{ columnCount }} 列
      </span>
    </div>

    <div v-if="truncated" class="table-viewer-truncated">
      文件较大，仅显示前 {{ MAX_ROWS }} 行
    </div>

    <div class="table-viewer-scroll">
      <table class="table-viewer-table">
        <thead v-if="header">
          <tr>
            <th class="table-viewer-index-col"></th>
            <th v-for="(cell, i) in header" :key="i" :title="cell">{{ cell }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, r) in bodyRows" :key="r">
            <td class="table-viewer-index-col">{{ r + 1 }}</td>
            <td
              v-for="c in columnCount"
              :key="c"
              :title="row[c - 1] ?? ''"
            >{{ row[c - 1] ?? '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped lang="scss">
.table-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.table-viewer-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  height: 34px;
  padding: 0 10px;
  border-bottom: 1px solid #ededed;
  font-size: 12px;
  color: #8c8c8c;
}

.table-viewer-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
}

.table-viewer-truncated {
  flex-shrink: 0;
  padding: 6px 10px;
  background: #fffbe6;
  border-bottom: 1px solid #ffe58f;
  font-size: 12px;
  color: #d48806;
}

.table-viewer-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.table-viewer-table {
  border-collapse: collapse;
  font-size: 12.5px;
  white-space: nowrap;

  th,
  td {
    max-width: 320px;
    overflow: hidden;
    text-overflow: ellipsis;
    padding: 5px 10px;
    border-right: 1px solid #f0f0f0;
    border-bottom: 1px solid #f0f0f0;
    text-align: left;
    color: #262626;
  }

  th {
    position: sticky;
    top: 0;
    z-index: 1;
    background: #fafafa;
    font-weight: 600;
    color: #595959;
  }

  tbody tr:hover td {
    background: rgba(15, 116, 255, 0.04);
  }
}

.table-viewer-index-col {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #fafafa;
  text-align: right !important;
  color: #bfbfbf !important;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.table-viewer-table thead .table-viewer-index-col {
  z-index: 2;
}
</style>
