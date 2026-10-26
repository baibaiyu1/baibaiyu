<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 定时任务（列表）
 *
 * 版式参考「青云Agent平台」的定时任务页：白底面板 + 「定时任务」标题 +
 * 过滤条（搜索任务名称 / 状态 + 新增 / 刷新 / 列设置）+ 表格 + 底部「共 N 条」分页。
 * 新增与编辑走 {@link AgentScheduleTaskModal}（弹窗形态）。
 *
 * 数据来源：`GET /runtime/job/page`（`type=AGENT` + `bizId=<当前智能体>`），
 * 任务名称 / 输入内容 / 结果推送都存放在任务自身的 `dataMap` JSON 里
 * （与「自动化任务」模块同源约定），因此本页不需要额外的表结构。
 *
 * @component
 */
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  DeleteOutlined,
  EditOutlined,
  InboxOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined
} from '@ant-design/icons-vue'
import type { JobInfo } from '@/types'
import * as jobApi from '@/api/job'
import * as workflowApi from '@/api/workflow'
import { useAccountStore } from '@/stores'
import SimpleSwitch from '@/components/common/SimpleSwitch.vue'
import AgentScheduleTaskModal from './AgentScheduleTaskModal.vue'
import { describeCron, isOnceCron } from './scheduleCron'

/**
 * Props 定义
 */
const props = withDefaults(defineProps<{
  /** 智能体 ID */
  agentId: string
  /** 智能体名称（写入新建任务的 dataMap.bizName） */
  agentName?: string
}>(), {
  agentName: ''
})

/**
 * Emits 定义
 */
const emit = defineEmits<{
  /** 任务变更成功（编排页据此静默刷新详情） */
  success: []
}>()

const accountStore = useAccountStore()

/* ===================== 权限（单轨权限点，与自动化任务一致） ===================== */

const canAdd = computed(() => accountStore.hasAnyPerm(['automation:job:add']))
const canEdit = computed(() => accountStore.hasAnyPerm(['automation:job:edit']))
const canRun = computed(() => accountStore.hasAnyPerm(['automation:job:run']))
const canRemove = computed(() => accountStore.hasAnyPerm(['automation:job:remove']))

/* ===================== 列表状态 ===================== */

const loading = ref(false)
const tasks = ref<JobInfo[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
/** 状态筛选：undefined = 全部 */
const statusFilter = ref<boolean | undefined>(undefined)

/** 打开弹窗（新增 = task 为 null） */
const modalOpen = ref(false)
const editingTask = ref<JobInfo | null>(null)

/** 状态切换中的任务 ID（单行 loading） */
const togglingId = ref<string | null>(null)

/** 通知渠道 id → 名称（列表「结果推送」列展示用） */
const channelNames = ref<Record<string, string>>({})

/** 表格行（由 JobInfo + dataMap 解析而来） */
interface ScheduleRow {
  id: string
  name: string
  input: string
  pushNames: string[]
  triggerType: 'CRON' | 'ONCE'
  cron: string
  cronText: string
  enabled: boolean
  raw: JobInfo
}

/**
 * 解析任务上的 dataMap（容错：非 JSON 时按空对象处理）
 * @param dataMap 任务的 dataMap 字符串
 */
function parseDataMap(dataMap?: string): Record<string, unknown> {
  if (!dataMap) return {}
  try {
    const parsed = JSON.parse(dataMap)
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

/** 表格数据 */
const rows = computed<ScheduleRow[]>(() => tasks.value.map(task => {
  const dataMap = parseDataMap(task.dataMap)
  const cron = task.cron || ''
  const pushIds = Array.isArray(dataMap.pushChannelIds)
    ? (dataMap.pushChannelIds as unknown[]).map(id => String(id))
    : []
  return {
    id: String(task.id),
    name: String(dataMap.jobName || '') || '未命名任务',
    input: String(dataMap.userPrompt || dataMap.input || ''),
    pushNames: pushIds.map(id => channelNames.value[id] || `已失效渠道 #${id}`),
    triggerType: dataMap.triggerType === 'ONCE' || isOnceCron(cron) ? 'ONCE' as const : 'CRON' as const,
    cron,
    cronText: describeCron(cron),
    enabled: task.enabled !== false,
    raw: task
  }
}))

/* ===================== 列定义与列设置 ===================== */

/** 全部列（操作列固定显示，不参与列设置） */
const allColumns = [
  { title: '任务名称', dataIndex: 'name', key: 'name', width: 190, ellipsis: true },
  { title: '输入内容', dataIndex: 'input', key: 'input', ellipsis: true },
  { title: '结果推送', dataIndex: 'pushNames', key: 'push', width: 170 },
  { title: '触发类型', dataIndex: 'triggerType', key: 'trigger', width: 108 },
  { title: '执行周期', dataIndex: 'cronText', key: 'cron', width: 200 },
  { title: '状态', dataIndex: 'enabled', key: 'status', width: 88 },
  { title: '操作', key: 'action', width: 128, align: 'right' as const }
]

/** 可在「列设置」里开关的列 */
const togglableColumns = allColumns.filter(column => column.key !== 'action')

/** 当前显示的列 key */
const visibleKeys = ref<string[]>(togglableColumns.map(column => column.key))

/** 实际渲染的列 */
const columns = computed(() => allColumns.filter(column =>
  column.key === 'action' || visibleKeys.value.includes(column.key)
))

/**
 * 切换列显示（按原始列顺序回填，避免列顺序随勾选顺序漂移）
 * @param key 列 key
 */
function toggleColumn(key: string) {
  const selected = new Set(visibleKeys.value)
  if (selected.has(key)) {
    // 至少保留一列，否则表格只剩「操作」会很怪
    if (selected.size === 1) return
    selected.delete(key)
  } else {
    selected.add(key)
  }
  visibleKeys.value = togglableColumns
    .filter(column => selected.has(column.key))
    .map(column => column.key)
}

/* ===================== 数据加载 ===================== */

/**
 * 加载通知渠道（用于「结果推送」列回显渠道名；停用的渠道也要能回显，故不按 enabled 过滤）
 */
async function loadChannels() {
  try {
    const response = await workflowApi.channels()
    const list = (response.data.data || []) as unknown as Array<{ id: string; name: string }>
    channelNames.value = Object.fromEntries(list.map(channel => [String(channel.id), channel.name]))
  } catch (e) {
    console.error('加载通知渠道失败:', e)
  }
}

/**
 * 加载当前智能体的定时任务
 */
async function loadTasks() {
  if (!props.agentId) return
  loading.value = true
  try {
    const response = await jobApi.page({
      page: page.value,
      size: pageSize.value,
      type: 'AGENT',
      bizId: props.agentId,
      keyword: keyword.value.trim() || undefined,
      enabled: statusFilter.value
    })
    const data = response.data.data
    tasks.value = data?.records || []
    total.value = data?.total || 0
  } catch (e) {
    console.error('加载定时任务失败:', e)
    tasks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 搜索 / 筛选：回到第一页重新查询
 */
function handleSearch() {
  page.value = 1
  void loadTasks()
}

/**
 * 清空搜索框时立即恢复完整列表（输入中不查，回车 / 点搜索才查）
 * @param e 输入事件
 */
function handleKeywordChange(e: Event) {
  const value = (e.target as HTMLInputElement)?.value
  if (!value) handleSearch()
}

/**
 * 分页变化
 * @param current 页码
 * @param size 每页数量
 */
function handlePageChange(current: number, size: number) {
  page.value = current
  pageSize.value = size
  void loadTasks()
}

/**
 * 刷新按钮（保留搜索条件）
 */
function handleRefresh() {
  void loadTasks()
}

/* ===================== 行操作 ===================== */

/**
 * 新增任务
 */
function handleCreate() {
  editingTask.value = null
  modalOpen.value = true
}

/**
 * 编辑任务
 * @param row 行数据
 */
function handleEdit(row: ScheduleRow) {
  editingTask.value = row.raw
  modalOpen.value = true
}

/**
 * 弹窗保存成功
 */
function handleSaved() {
  void loadTasks()
  emit('success')
}

/**
 * 启用 / 禁用
 * @param row 行数据
 */
async function handleToggle(row: ScheduleRow) {
  togglingId.value = row.id
  try {
    await jobApi.toggle(row.id)
    message.success(row.enabled ? '已禁用' : '已启用')
    await loadTasks()
    emit('success')
  } catch (e) {
    console.error('切换定时任务状态失败:', e)
  } finally {
    togglingId.value = null
  }
}

/**
 * 手动触发一次
 * @param row 行数据
 */
function handleTrigger(row: ScheduleRow) {
  Modal.confirm({
    title: '执行一次',
    content: `确认立即执行「${row.name}」吗？`,
    okText: '执行',
    cancelText: '取消',
    onOk: async () => {
      try {
        message.loading('正在执行...', 0)
        await jobApi.trigger(row.id)
        message.destroy()
        message.success('已触发执行')
        await loadTasks()
      } catch (e) {
        message.destroy()
        console.error('触发定时任务失败:', e)
      }
    }
  })
}

/**
 * 删除任务
 * @param row 行数据
 */
function handleDelete(row: ScheduleRow) {
  Modal.confirm({
    title: '删除定时任务',
    content: `删除后无法恢复，确认删除「${row.name}」吗？`,
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      try {
        await jobApi.remove(row.id)
        message.success('删除成功')
        // 删掉本页最后一条时回退一页，避免停在空页
        if (tasks.value.length === 1 && page.value > 1) page.value -= 1
        await loadTasks()
        emit('success')
      } catch (e) {
        console.error('删除定时任务失败:', e)
      }
    }
  })
}

/**
 * 首次进入：加载渠道（结果推送列回显）与任务列表
 */
onMounted(() => {
  void loadChannels()
  void loadTasks()
})

/**
 * 提供给旧配置面板（AgentConfigPanel）的未保存标记：
 * 本页改成列表 + 弹窗后，「新增 / 编辑」在弹窗内即时保存，不存在页内未保存状态。
 */
const isDirty = computed(() => false)

defineExpose({ isDirty, refresh: loadTasks })
</script>

<template>
  <div class="agsched">
    <!-- 标题 -->
    <header class="agsched__head">
      <h3 class="agsched__title">定时任务</h3>
      <span class="agsched__subtitle">到点后自动向该智能体发送消息，结果始终保存在内部会话中</span>
    </header>

    <!-- 过滤 + 操作 -->
    <div class="agsched__toolbar">
      <div class="agsched__filters">
        <AInput
          v-model:value="keyword"
          class="agsched__search"
          placeholder="搜索任务名称"
          allow-clear
          @press-enter="handleSearch"
          @change="handleKeywordChange"
        >
          <template #prefix><SearchOutlined /></template>
        </AInput>

        <ASelect
          v-model:value="statusFilter"
          class="agsched__status"
          placeholder="状态"
          allow-clear
          :options="[
            { label: '启用', value: true },
            { label: '禁用', value: false }
          ]"
          @change="handleSearch"
        />
      </div>

      <div class="agsched__actions">
        <AButton v-if="canAdd" type="primary" @click="handleCreate">
          <template #icon><PlusOutlined /></template>
          新增
        </AButton>

        <AButton :loading="loading" @click="handleRefresh">
          <template #icon><ReloadOutlined /></template>
          刷新
        </AButton>

        <ADropdown :trigger="['click']" placement="bottomRight">
          <AButton>
            <template #icon><SettingOutlined /></template>
            列设置
          </AButton>
          <template #overlay>
            <div class="agsched__columns" @click.stop>
              <label v-for="column in togglableColumns" :key="column.key" class="agsched__columns-item">
                <ACheckbox
                  :checked="visibleKeys.includes(column.key)"
                  @change="toggleColumn(column.key)"
                />
                <span>{{ column.title }}</span>
              </label>
            </div>
          </template>
        </ADropdown>
      </div>
    </div>

    <!-- 表格 -->
    <div class="agsched__table">
      <ATable
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="middle"
      >
        <template #emptyText>
          <div class="agsched__empty">
            <InboxOutlined class="agsched__empty-icon" />
            <span>无数据</span>
          </div>
        </template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span class="agsched__name" :title="record.name">{{ record.name }}</span>
          </template>

          <template v-else-if="column.key === 'input'">
            <span v-if="record.input" class="agsched__text" :title="record.input">{{ record.input }}</span>
            <span v-else class="agsched__placeholder">—</span>
          </template>

          <template v-else-if="column.key === 'push'">
            <div v-if="record.pushNames.length" class="agsched__tags">
              <ATag v-for="name in record.pushNames" :key="name" :bordered="false" color="blue">
                {{ name }}
              </ATag>
            </div>
            <span v-else class="agsched__placeholder">—</span>
          </template>

          <template v-else-if="column.key === 'trigger'">
            <span
              class="agsched__trigger"
              :class="record.triggerType === 'ONCE' ? 'agsched__trigger--once' : 'agsched__trigger--cron'"
            >
              {{ record.triggerType === 'ONCE' ? '一次性任务' : '周期任务' }}
            </span>
          </template>

          <template v-else-if="column.key === 'cron'">
            <div class="agsched__cron">
              <span :title="record.cronText">{{ record.cronText }}</span>
              <em :title="record.cron">{{ record.cron }}</em>
            </div>
          </template>

          <template v-else-if="column.key === 'status'">
            <SimpleSwitch
              :checked="record.enabled"
              :loading="togglingId === record.id"
              :disabled="!canEdit"
              @change="handleToggle(record)"
            />
          </template>

          <template v-else-if="column.key === 'action'">
            <div class="agsched__ops">
              <ATooltip v-if="canRun" title="执行一次">
                <AButton type="text" size="small" @click="handleTrigger(record)">
                  <template #icon><PlayCircleOutlined /></template>
                </AButton>
              </ATooltip>
              <ATooltip v-if="canEdit" title="编辑">
                <AButton type="text" size="small" @click="handleEdit(record)">
                  <template #icon><EditOutlined /></template>
                </AButton>
              </ATooltip>
              <ATooltip v-if="canRemove" title="删除">
                <AButton type="text" size="small" danger @click="handleDelete(record)">
                  <template #icon><DeleteOutlined /></template>
                </AButton>
              </ATooltip>
            </div>
          </template>
        </template>
      </ATable>
    </div>

    <!-- 分页 -->
    <footer class="agsched__foot">
      <span class="agsched__total">共 {{ total }} 条</span>
      <APagination
        :current="page"
        :page-size="pageSize"
        :total="total"
        :page-size-options="['10', '20', '50']"
        show-size-changer
        size="small"
        @change="handlePageChange"
      />
    </footer>

    <!-- 新增 / 编辑弹窗 -->
    <AgentScheduleTaskModal
      v-model:open="modalOpen"
      :agent-id="agentId"
      :agent-name="agentName"
      :task="editingTask"
      @saved="handleSaved"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/agent/schedule.scss' as *;
</style>
