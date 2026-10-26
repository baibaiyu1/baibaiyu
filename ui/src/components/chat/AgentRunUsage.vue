<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, useId } from 'vue'
import { BarChartOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { getTelemetry, type UsageRun, type UsageRecord } from '@/api/agui/telemetry'
import ChatRunPanel from './ChatRunPanel.vue'

const props = defineProps<{ sessionId: string; agentId?: string; isRunning: boolean }>()
const runs = ref<UsageRun[]>([])
const records = ref<UsageRecord[]>([])
const error = ref('')
const loading = ref(false)
const open = ref(false)
const tab = ref('runs')
const search = ref('')
const runFilter = ref('all')
const typeFilter = ref('all')
const stateFilter = ref('all')
const page = ref(1)
const pageSize = 12
const filterId = useId()
const latestRun = computed(() => runs.value[0])
const value = (v: string | number | null | undefined) => v == null ? '未知' : Number(v).toLocaleString()
const status = (v: string | null) => ({ RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', STOPPED: '已停止', CANCELLED: '已取消', WAITING: '待确认' }[v || ''] || '未确认')
const statusColor = (v: string | null) => ({ RUNNING: 'processing', SUCCEEDED: 'success', FAILED: 'error', WAITING: 'warning' }[v || ''] || 'default')
const elapsed = (v: number | string | null, state: string | null) => v == null
  ? (state === 'RUNNING' ? '进行中' : '未知')
  : Number(v) < 1000 ? `${Number(v)} 毫秒` : `${(Number(v) / 1000).toFixed(1)} 秒`
const time = (v?: string | null) => v && dayjs(v).isValid() ? dayjs(v).format('MM-DD HH:mm:ss') : '时间未知'
const operationLabel = (item: UsageRecord) => item.operation === 'MODEL'
  ? (props.agentId && item.agentId !== props.agentId ? '子智能体' : '主模型')
  : ({ RUN: '任务执行', COMPACTION: '摘要压缩', VOICE: '语音对话', MODEL_RETRY: '模型重试', MODEL_SWITCH: '切换模型' }[item.operation] || item.operation)
const isModelCall = (item: UsageRecord) => ['MODEL', 'COMPACTION', 'VOICE'].includes(item.operation)
const runOptions = computed(() => [{ value: 'all', label: '全部任务' }, ...runs.value.map(run => ({ value: run.runId, label: `${time(run.startedAt)} · ${run.runId.slice(0, 8)}` }))])
const filteredRecords = computed(() => {
  const query = search.value.trim().toLowerCase()
  return records.value.filter(item =>
    (runFilter.value === 'all' || item.runId === runFilter.value)
    && (typeFilter.value === 'all' || item.operation === typeFilter.value)
    && (stateFilter.value === 'all' || item.state === stateFilter.value)
    && (!query || [item.modelName, operationLabel(item), item.agentId, item.runId, item.errorType, item.errorStage].some(v => v?.toLowerCase().includes(query))))
})
const pageRecords = computed(() => filteredRecords.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const hasFilters = computed(() => Boolean(search.value || runFilter.value !== 'all' || typeFilter.value !== 'all' || stateFilter.value !== 'all'))

let generation = 0, disposed = false, finalRefreshes = 0, timer: ReturnType<typeof setTimeout> | undefined
async function refresh() {
  const version = ++generation, id = props.sessionId
  clearTimeout(timer)
  loading.value = true
  try {
    const result = await getTelemetry(id)
    if (disposed || generation !== version) return
    runs.value = result.runs
    records.value = result.records
    error.value = ''
  } catch (e) {
    if (!disposed && generation === version) error.value = e instanceof Error ? e.message : '查询失败，请重试'
  } finally {
    if (!disposed && generation === version) {
      loading.value = false
      if (!document.hidden) {
        if (props.isRunning) timer = setTimeout(refresh, 3000)
        else if (finalRefreshes > 0) {
          finalRefreshes--
          timer = setTimeout(refresh, 800)
        } else if (open.value) timer = setTimeout(refresh, 15000)
      }
    }
  }
}
function clearFilters() {
  search.value = ''; runFilter.value = 'all'; typeFilter.value = 'all'; stateFilter.value = 'all'
}
function showRunRecords(runId: string) {
  clearFilters()
  runFilter.value = runId
  tab.value = 'records'
}
watch(() => props.sessionId, () => {
  finalRefreshes = 0
  runs.value = []; records.value = []; error.value = ''; open.value = false; tab.value = 'runs'
  clearFilters()
  void refresh()
}, { immediate: true })
watch(() => props.isRunning, (running, wasRunning) => {
  // 最后的用量落库可能稍晚于流结束，短暂补查后停止后台轮询。
  finalRefreshes = !running && wasRunning ? 3 : 0
  void refresh()
})
watch(open, visible => {
  if (visible) void refresh()
  else if (!props.isRunning) clearTimeout(timer)
})
watch([search, runFilter, typeFilter, stateFilter], () => { page.value = 1 })
watch(() => filteredRecords.value.length, total => { page.value = Math.min(page.value, Math.max(1, Math.ceil(total / pageSize))) })
const onVisibilityChange = () => {
  clearTimeout(timer)
  if (!document.hidden) void refresh()
}
document.addEventListener('visibilitychange', onVisibilityChange)
onBeforeUnmount(() => {
  disposed = true; generation++; clearTimeout(timer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <ChatRunPanel v-model:open="open" class="model-usage-panel" title="实际模型用量" :width="920">
    <template #icon><BarChartOutlined /></template>
    <template #summary>
      <span v-if="latestRun" class="usage-inline" :class="{ 'is-stale': error }">
        <span class="usage-inline-scope">最近一轮<span v-if="Number(latestRun.unknownCalls) > 0"> · 已知小计</span></span>
        <span>输入 <strong>{{ value(latestRun.inputTokens) }}</strong></span>
        <span>输出 <strong>{{ value(latestRun.outputTokens) }}</strong> Token</span>
        <span class="usage-inline-secondary">{{ value(latestRun.modelCalls) }} 次调用<span v-if="isRunning"> · 更新中</span></span>
        <span v-if="Number(latestRun.unknownCalls) > 0" class="usage-inline-incomplete">{{ value(latestRun.unknownCalls) }} 次未知</span>
      </span>
      <span v-else-if="error">查询失败，点击重试</span>
      <span v-else-if="loading">加载中</span>
      <span v-else>暂无用量</span>
      <span v-if="error && latestRun" class="usage-inline-incomplete">刷新失败</span>
    </template>
    <template #extra>
      <AButton type="text" size="small" :loading="loading" aria-label="刷新模型用量" @click="refresh"><ReloadOutlined /></AButton>
    </template>
    <div class="usage-intro">
      <p>供应商返回的实际 Token 用量</p>
      <span v-if="isRunning" class="usage-live"><span aria-hidden="true" />运行中 · 自动更新</span>
    </div>
    <AAlert v-if="error" class="usage-alert" type="error" show-icon :message="error" role="alert" />
    <div v-if="latestRun" class="latest-run">
      <div class="latest-run-heading">
        <div><strong>最近任务</strong><span class="run-time">{{ time(latestRun.startedAt) }}</span></div>
        <ATag :color="statusColor(latestRun.state)" :bordered="false">{{ status(latestRun.state) }}</ATag>
      </div>
      <div class="usage-metrics">
        <div class="usage-metric"><span>模型调用</span><strong>{{ value(latestRun.modelCalls) }}<small> 次</small></strong></div>
        <div class="usage-metric"><span>输入 Token <small v-if="Number(latestRun.unknownCalls) > 0">已知小计</small></span><strong>{{ value(latestRun.inputTokens) }}</strong></div>
        <div class="usage-metric"><span>输出 Token <small v-if="Number(latestRun.unknownCalls) > 0">已知小计</small></span><strong>{{ value(latestRun.outputTokens) }}</strong></div>
        <div class="usage-metric"><span>任务耗时</span><strong class="duration-value">{{ elapsed(latestRun.elapsedMs, latestRun.state) }}</strong></div>
      </div>
      <p v-if="Number(latestRun.unknownCalls) > 0" class="usage-incomplete">{{ value(latestRun.unknownCalls) }} 次调用尚未完成或未返回用量，以上为已知小计。</p>
    </div>
    <ASpin v-if="loading && !runs.length && !records.length" class="usage-loading" tip="正在加载用量" />
    <ATabs v-else v-model:active-key="tab" class="usage-tabs">
      <ATabPane key="runs" :tab="`任务汇总 · ${runs.length}`">
        <div class="section-caption"><span>最近 20 轮任务</span><span>点击“查看调用”核对明细</span></div>
        <AEmpty v-if="!runs.length" description="暂无任务用量，发送消息后会自动记录" />
        <div v-else class="usage-table-wrap" tabindex="0" aria-label="任务用量汇总，可横向滚动">
          <table class="usage-table run-table">
            <thead><tr><th scope="col">任务</th><th scope="col">状态 / 耗时</th><th scope="col" class="numeric">调用</th><th scope="col" class="numeric">输入 Token</th><th scope="col" class="numeric">输出 Token</th><th scope="col" class="numeric">推理 Token</th><th scope="col" class="numeric">重试 / 切换</th><th scope="col">明细</th></tr></thead>
            <tbody><tr v-for="run in runs" :key="run.runId">
              <td><span class="cell-primary">{{ time(run.startedAt) }}</span><span class="cell-secondary" :title="run.runId">{{ run.runId.slice(0, 8) }}</span></td>
              <td><ATag :color="statusColor(run.state)" :bordered="false">{{ status(run.state) }}</ATag><span class="cell-secondary">{{ elapsed(run.elapsedMs, run.state) }}</span></td>
              <td class="numeric">{{ value(run.modelCalls) }}<span v-if="Number(run.unknownCalls) > 0" class="cell-secondary incomplete" :title="`${value(run.unknownCalls)} 次调用用量未知；Token 显示已知小计`">{{ value(run.unknownCalls) }} 次未知</span></td>
              <td class="numeric" :class="{ unknown: run.inputTokens == null }">{{ value(run.inputTokens) }}</td>
              <td class="numeric" :class="{ unknown: run.outputTokens == null }">{{ value(run.outputTokens) }}</td>
              <td class="numeric" :class="{ unknown: run.reasoningTokens == null }">{{ value(run.reasoningTokens) }}</td>
              <td class="numeric">{{ value(run.retries) }} / {{ value(run.switches) }}</td>
              <td><button type="button" class="view-records" :aria-label="`查看任务 ${run.runId.slice(0, 8)} 的调用`" @click="showRunRecords(run.runId)">查看调用</button></td>
            </tr></tbody>
          </table>
        </div>
      </ATabPane>
      <ATabPane key="records" :tab="`调用记录 · ${records.length}`">
        <div class="record-filters">
          <AInput v-model:value="search" allow-clear placeholder="搜索模型或错误类型" aria-label="搜索调用记录" />
          <label class="filter-label" :for="`${filterId}-run`">按任务筛选</label>
          <ASelect :id="`${filterId}-run`" v-model:value="runFilter" :options="runOptions" />
          <label class="filter-label" :for="`${filterId}-type`">按调用类型筛选</label>
          <ASelect :id="`${filterId}-type`" v-model:value="typeFilter" :options="[
            { value: 'all', label: '全部类型' }, { value: 'MODEL', label: '模型调用' }, { value: 'COMPACTION', label: '摘要压缩' }, { value: 'VOICE', label: '语音对话' },
            { value: 'MODEL_RETRY', label: '模型重试' }, { value: 'MODEL_SWITCH', label: '切换模型' }, { value: 'RUN', label: '任务执行' },
          ]" />
          <label class="filter-label" :for="`${filterId}-state`">按调用状态筛选</label>
          <ASelect :id="`${filterId}-state`" v-model:value="stateFilter" :options="[
            { value: 'all', label: '全部状态' }, { value: 'RUNNING', label: '运行中' }, { value: 'SUCCEEDED', label: '已完成' },
            { value: 'FAILED', label: '失败' }, { value: 'CANCELLED', label: '已取消' }, { value: 'STOPPED', label: '已停止' }, { value: 'WAITING', label: '待确认' },
          ]" />
        </div>
        <div class="section-caption"><span>最近 200 条 · 符合条件 {{ filteredRecords.length }} 条</span><button v-if="hasFilters" type="button" class="view-records" @click="clearFilters">清除筛选</button></div>
        <AEmpty v-if="!pageRecords.length" :description="hasFilters ? '没有符合条件的调用记录' : '暂无调用记录'" />
        <div v-else class="usage-table-wrap" tabindex="0" aria-label="模型调用明细，可横向滚动">
          <table class="usage-table record-table">
            <thead><tr><th scope="col">时间</th><th scope="col">类型 / 模型</th><th scope="col">状态 / 耗时</th><th scope="col" class="numeric">输入 Token</th><th scope="col" class="numeric">输出 Token</th><th scope="col" class="numeric">推理 Token</th></tr></thead>
            <tbody><tr v-for="item in pageRecords" :key="item.id">
              <td><span class="cell-primary">{{ time(item.startedAt) }}</span><span class="cell-secondary" :title="item.runId">任务 {{ item.runId.slice(0, 8) }}</span></td>
              <td class="model-cell"><span class="cell-primary">{{ operationLabel(item) }}</span><span class="cell-secondary model-name" :title="item.modelName || `智能体 ${item.agentId}`">{{ item.modelName || `智能体 ${item.agentId}` }}</span></td>
              <td><ATag :color="statusColor(item.state)" :bordered="false">{{ status(item.state) }}</ATag><span class="cell-secondary">{{ elapsed(item.elapsedMs, item.state) }}</span><span v-if="item.errorType" class="cell-error" :title="item.errorStage || undefined">{{ item.errorType }}</span></td>
              <td class="numeric" :class="{ unknown: item.inputTokens == null }">{{ isModelCall(item) ? value(item.inputTokens) : '—' }}</td>
              <td class="numeric" :class="{ unknown: item.outputTokens == null }">{{ isModelCall(item) ? value(item.outputTokens) : '—' }}</td>
              <td class="numeric" :class="{ unknown: item.reasoningTokens == null }">{{ isModelCall(item) ? value(item.reasoningTokens) : '—' }}</td>
            </tr></tbody>
          </table>
        </div>
        <APagination v-if="filteredRecords.length > pageSize" v-model:current="page" class="record-pagination" size="small" :page-size="pageSize" :total="filteredRecords.length" :show-size-changer="false" :show-less-items="true" />
      </ATabPane>
    </ATabs>
    <div class="usage-footnote">
      <p>Token 来自供应商响应，与上下文占用估算不同。推理 Token 通常已包含在输出 Token 中，不再相加。</p>
      <p>“未知”表示尚未完成或供应商未返回；有未知调用时，小计不代表完整用量。“—”表示该事件不产生模型用量。</p>
    </div>
  </ChatRunPanel>
</template>

<style scoped>
.model-usage-panel :deep(.run-panel-trigger) { flex-wrap: wrap; text-align: left; white-space: normal; }
.model-usage-panel :deep(.trigger-summary) { min-width: 0; }
.usage-inline { display: inline-flex; align-items: baseline; flex-wrap: wrap; gap: 2px 10px; color: var(--color-text-regular, #64748b); }
.usage-inline strong { font-weight: 600; color: var(--color-text-primary, #1e293b); font-variant-numeric: tabular-nums; }
.usage-inline-scope, .usage-inline-secondary { color: #8792a2; }
.usage-inline-incomplete { color: #ad6800; }
.usage-inline.is-stale { opacity: .65; }
.usage-intro { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; color: var(--color-text-regular, #64748b); font-size: 13px; }
.usage-intro p { margin: 0; }
.usage-live { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; font-size: 12px; color: #0f74ff; }
.usage-live > span { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.usage-alert { margin-bottom: 16px; }
.latest-run { padding: 16px 18px; border: 1px solid var(--color-border-extra-light, #e9edf3); border-radius: 12px; background: #f8fafc; }
.latest-run-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.latest-run-heading strong { color: var(--color-text-primary, #1e293b); font-size: 13px; }
.run-time { margin-left: 12px; color: #8792a2; font-size: 12px; }
.usage-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 20px; }
.usage-metric { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.usage-metric > span { font-size: 12px; color: var(--color-text-regular, #64748b); }
.usage-metric strong { font-size: 24px; line-height: 1.3; font-weight: 600; color: var(--color-text-primary, #1e293b); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.usage-metric small { font-size: 11px; font-weight: 400; color: #8792a2; }
.usage-metric .duration-value { font-size: 20px; }
.usage-incomplete { margin: 14px 0 0; color: #ad6800; font-size: 12px; line-height: 1.7; }
.usage-tabs { margin-top: 16px; }
.section-caption { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: var(--color-text-regular, #64748b); font-size: 12px; }
.usage-table-wrap { overflow-x: auto; border: 1px solid var(--color-border-extra-light, #eef0f3); border-radius: 10px; }
.usage-table-wrap:focus-visible { outline: 2px solid #0f74ff; outline-offset: 2px; }
.usage-table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
.run-table { min-width: 790px; }
.record-table { min-width: 700px; }
.usage-table th { padding: 11px 12px; background: #f8fafc; font-weight: 500; color: var(--color-text-regular, #64748b); white-space: nowrap; }
.usage-table td { padding: 12px; border-top: 1px solid var(--color-border-extra-light, #eef0f3); color: var(--color-text-primary, #1e293b); vertical-align: top; }
.usage-table tr:hover td { background: #fafcff; }
.usage-table .numeric { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.usage-table .unknown { color: #8792a2; }
.cell-primary { display: block; white-space: nowrap; }
.cell-secondary { display: block; margin-top: 5px; color: var(--color-text-regular, #64748b); font-size: 11px; white-space: nowrap; }
.cell-error { display: block; margin-top: 6px; max-width: 180px; color: #cf1322; overflow-wrap: anywhere; }
.cell-secondary.incomplete { color: #ad6800; }
.model-name { max-width: 190px; overflow: hidden; text-overflow: ellipsis; }
.model-cell { min-width: 140px; }
.view-records { border: 0; padding: 0; background: none; color: #0f74ff; font: inherit; cursor: pointer; white-space: nowrap; }
.view-records:hover { text-decoration: underline; }
.view-records:focus-visible { outline: 2px solid #0f74ff; outline-offset: 3px; }
.record-filters { display: grid; grid-template-columns: minmax(170px, 1.5fr) minmax(180px, 1.5fr) minmax(120px, 1fr) minmax(120px, 1fr); gap: 8px; margin-bottom: 14px; }
.record-filters > * { min-width: 0; }
.filter-label { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.record-pagination { display: flex; justify-content: flex-end; flex-wrap: wrap; margin-top: 16px; }
.usage-footnote { margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--color-border-extra-light, #eef0f3); color: var(--color-text-regular, #64748b); font-size: 12px; line-height: 1.8; }
.usage-footnote p { margin: 4px 0; }
.usage-loading { display: block; padding: 60px 0; }
@media (max-width: 640px) {
  .usage-intro { align-items: flex-start; flex-direction: column; gap: 6px; }
  .usage-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 12px; }
  .usage-metric strong { font-size: 22px; }
  .run-time { display: block; margin: 5px 0 0; }
  .record-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .section-caption { align-items: flex-start; }
}
</style>
