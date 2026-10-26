<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 审批工作台（通用组件）
 *
 * 「审查智能体」「审查工作流」共用：待我审批 / 我发起的 / 全部 三个分域，
 * 支持通过、拒绝（可填意见）、撤回，以及按资源类型配置审批人开关。
 * 通过审批后由后端将资源置为已发布状态。
 *
 * @component
 */
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  AuditOutlined,
  CheckOutlined,
  CloseOutlined,
  ReloadOutlined,
  SettingOutlined,
  UndoOutlined
} from '@ant-design/icons-vue'
import * as approvalApi from '@/api/approval'
import * as systemUserApi from '@/api/systemUser'
import type { ApprovalFlow, ApprovalRequestVO, ApprovalResourceTypeOption } from '@/types/approval'
import type { SystemUserVO } from '@/types/system'

const props = withDefaults(defineProps<{
  /** 页面标题 */
  title: string
  /** 页面说明 */
  description?: string
  /** 限定资源类型；不传表示展示全部可审批类型 */
  resourceType?: string
}>(), {
  description: '',
  resourceType: ''
})

const emit = defineEmits<{
  /** 审批数据发生变化（通过 / 拒绝 / 撤回） */
  changed: []
}>()

type ScopeKey = 'pending' | 'mine' | 'all'

/** 分域页签 */
const scopes: Array<{ key: ScopeKey; label: string }> = [
  { key: 'pending', label: '待我审批' },
  { key: 'mine', label: '我发起的' },
  { key: 'all', label: '全部' }
]

/** 状态筛选项 */
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'CANCELED', label: '已取消' }
]

const scope = ref<ScopeKey>('pending')
const status = ref('')
const keyword = ref('')
const loading = ref(false)
const records = ref<ApprovalRequestVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

/**
 * 加载审批申请单
 */
async function load() {
  loading.value = true
  try {
    const response = await approvalApi.page({
      page: current.value,
      size: pageSize.value,
      scope: scope.value,
      status: status.value || undefined,
      resourceType: props.resourceType || undefined,
      keyword: keyword.value.trim() || undefined
    })
    const data = response.data.data
    records.value = data?.records || []
    total.value = data?.total || 0
  } catch (e) {
    console.error('加载审批申请失败:', e)
  } finally {
    loading.value = false
  }
}

/**
 * 切换分域 / 筛选后回到第一页重新加载
 */
function reload() {
  current.value = 1
  void load()
}

/**
 * 状态标签颜色
 */
function statusColor(item: ApprovalRequestVO) {
  switch (item.status) {
    case 'PENDING':
      return 'orange'
    case 'APPROVED':
      return 'green'
    case 'REJECTED':
      return 'red'
    default:
      return 'default'
  }
}

/* ===================== 通过 / 拒绝 ===================== */

const handleOpen = ref(false)
const handleAction = ref<'approve' | 'reject'>('approve')
const handleOpinion = ref('')
const activeItem = ref<ApprovalRequestVO | null>(null)
const handling = ref(false)

const handleTitle = computed(() => (handleAction.value === 'approve' ? '通过发布审批' : '拒绝发布审批'))

function openHandle(item: ApprovalRequestVO, action: 'approve' | 'reject') {
  activeItem.value = item
  handleAction.value = action
  handleOpinion.value = ''
  handleOpen.value = true
}

async function submitHandle() {
  const item = activeItem.value
  if (!item) return
  const opinion = handleOpinion.value.trim()
  if (handleAction.value === 'reject' && !opinion) {
    message.warning('拒绝时请填写审批意见')
    return
  }
  handling.value = true
  try {
    if (handleAction.value === 'approve') {
      await approvalApi.approve(item.id, opinion)
      message.success('已通过，资源已发布')
    } else {
      await approvalApi.reject(item.id, opinion)
      message.success('已拒绝')
    }
    handleOpen.value = false
    await load()
    emit('changed')
  } catch (e) {
    console.error('审批处理失败:', e)
  } finally {
    handling.value = false
  }
}

/**
 * 撤回自己发起的申请
 */
function askCancel(item: ApprovalRequestVO) {
  Modal.confirm({
    title: '撤回申请',
    content: `确认撤回「${item.resourceName || item.resourceId}」的发布申请？`,
    okText: '确认撤回',
    cancelText: '取消',
    onOk: async () => {
      await approvalApi.cancel(item.id)
      message.success('已撤回')
      await load()
      emit('changed')
    }
  })
}

/* ===================== 审批配置 ===================== */

interface FlowRow {
  resourceType: string
  label: string
  enabled: boolean
  approverIds: Array<string | number>
  approverNames: string[]
  remark: string
}

const flowOpen = ref(false)
const flowLoading = ref(false)
const flowSaving = ref(false)
const flowRows = ref<FlowRow[]>([])
const users = ref<SystemUserVO[]>([])
const typeOptions = ref<ApprovalResourceTypeOption[]>([])

/** 审批人候选项 */
const userOptions = computed(() =>
  users.value.map((item) => ({
    value: item.id,
    label: item.nickname || item.username || String(item.id)
  }))
)

async function openFlow() {
  flowOpen.value = true
  flowLoading.value = true
  try {
    const [flowRes, userRes, typeRes] = await Promise.all([
      approvalApi.listFlows(),
      systemUserApi.list(),
      approvalApi.resourceTypes()
    ])
    users.value = userRes.data.data || []
    typeOptions.value = typeRes.data.data || []
    const flowList = flowRes.data.data || []
    const labelMap = new Map(typeOptions.value.map((item) => [item.value, item.label]))
    flowRows.value = flowList
      .filter((flow: ApprovalFlow) => !props.resourceType || flow.resourceType === props.resourceType)
      .map((flow: ApprovalFlow) => ({
        resourceType: flow.resourceType,
        label: labelMap.get(flow.resourceType) || flow.resourceType,
        enabled: flow.enabled === true,
        approverIds: flow.approverIds
          ? flow.approverIds.split(',').map((id) => id.trim()).filter(Boolean)
          : [],
        approverNames: flow.approverNames
          ? flow.approverNames.split(',').map((name) => name.trim()).filter(Boolean)
          : [],
        remark: flow.remark || ''
      }))
  } catch (e) {
    console.error('加载审批配置失败:', e)
  } finally {
    flowLoading.value = false
  }
}

async function saveFlowRow(row: FlowRow) {
  if (row.enabled && !row.approverIds.length) {
    message.warning('启用审批时必须至少选择一名审批人')
    return
  }
  flowSaving.value = true
  try {
    const approverNames = row.approverIds.map((id) => {
      const hit = userOptions.value.find((item) => String(item.value) === String(id))
      return hit?.label || String(id)
    })
    await approvalApi.saveFlow({
      resourceType: row.resourceType,
      enabled: row.enabled,
      approverIds: row.approverIds,
      approverNames,
      remark: row.remark
    })
    message.success(`${row.label} 审批配置已保存`)
    row.approverNames = approverNames
  } catch (e) {
    console.error('保存审批配置失败:', e)
  } finally {
    flowSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="approval-workbench">
    <div class="page-header">
      <h1 class="page-title">
        <AuditOutlined />
        {{ title }}
      </h1>
      <p class="page-desc">{{ description }}</p>
    </div>

    <div class="page-content">
      <div class="approval-toolbar">
        <div class="approval-tabs">
          <button
            v-for="item in scopes"
            :key="item.key"
            class="approval-tab"
            :class="{ active: scope === item.key }"
            @click="scope = item.key; reload()"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="approval-filters">
          <a-select
            v-model:value="status"
            :options="statusOptions"
            style="width: 140px"
            @change="reload"
          />
          <a-input
            v-model:value="keyword"
            placeholder="资源名称 / 申请人"
            allow-clear
            style="width: 200px"
            @press-enter="reload"
          />
          <a-button @click="reload">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
          <a-button @click="openFlow">
            <template #icon><SettingOutlined /></template>
            审批配置
          </a-button>
        </div>
      </div>

      <a-spin :spinning="loading">
        <div v-if="records.length" class="approval-list">
          <article v-for="item in records" :key="item.id" class="approval-item">
            <div class="approval-item__main">
              <div class="approval-item__title">
                <strong>{{ item.resourceName || item.resourceId }}</strong>
                <a-tag :color="statusColor(item)">{{ item.statusDesc || item.status }}</a-tag>
                <span class="approval-item__type">{{ item.resourceTypeDesc || item.resourceType }}</span>
                <span v-if="item.resourceVersion" class="approval-item__version">{{ item.resourceVersion }}</span>
              </div>
              <div class="approval-item__meta">
                <span>申请人：{{ item.applicantName || '-' }}</span>
                <span>申请时间：{{ item.createdAt || '-' }}</span>
                <span v-if="item.handledAt">处理时间：{{ item.handledAt }}</span>
                <span v-if="item.approverName">处理人：{{ item.approverName }}</span>
              </div>
              <div v-if="item.remark" class="approval-item__remark">申请说明：{{ item.remark }}</div>
              <div v-if="item.opinion" class="approval-item__opinion">审批意见：{{ item.opinion }}</div>
            </div>
            <div class="approval-item__actions">
              <template v-if="item.handleable">
                <a-button type="primary" size="small" @click="openHandle(item, 'approve')">
                  <template #icon><CheckOutlined /></template>
                  通过
                </a-button>
                <a-button danger size="small" @click="openHandle(item, 'reject')">
                  <template #icon><CloseOutlined /></template>
                  拒绝
                </a-button>
              </template>
              <a-button
                v-else-if="item.mine && item.status === 'PENDING'"
                size="small"
                @click="askCancel(item)"
              >
                <template #icon><UndoOutlined /></template>
                撤回
              </a-button>
              <span v-else class="approval-item__done">{{ item.statusDesc || item.status }}</span>
            </div>
          </article>
        </div>

        <a-empty v-else description="暂无审批记录" />
      </a-spin>

      <div v-if="total > 0" class="approval-pagination">
        <a-pagination
          v-model:current="current"
          v-model:page-size="pageSize"
          :total="total"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          @change="load"
        />
      </div>
    </div>

    <!-- 审批处理 -->
    <a-modal
      v-model:open="handleOpen"
      :title="handleTitle"
      :confirm-loading="handling"
      :ok-text="handleAction === 'approve' ? '确认通过并发布' : '确认拒绝'"
      cancel-text="取消"
      @ok="submitHandle"
    >
      <p class="approval-modal__tip">
        {{ handleAction === 'approve'
          ? '通过后该资源将立即发布生效。'
          : '拒绝后资源保持未发布状态，申请人可修改后重新提交。' }}
      </p>
      <a-textarea
        v-model:value="handleOpinion"
        :rows="3"
        :maxlength="500"
        show-count
        :placeholder="handleAction === 'approve' ? '审批意见（选填）' : '请填写拒绝原因（必填）'"
      />
    </a-modal>

    <!-- 审批配置 -->
    <a-modal
      v-model:open="flowOpen"
      title="审批配置"
      :footer="null"
      width="720px"
    >
      <a-spin :spinning="flowLoading">
        <div v-if="flowRows.length" class="approval-flow-list">
          <div v-for="row in flowRows" :key="row.resourceType" class="approval-flow-item">
            <div class="approval-flow-item__head">
              <strong>{{ row.label }}</strong>
              <a-switch v-model:checked="row.enabled" checked-children="启用审批" un-checked-children="直接发布" />
            </div>
            <div class="approval-flow-item__body">
              <a-select
                v-model:value="row.approverIds"
                mode="multiple"
                :options="userOptions"
                :disabled="!row.enabled"
                placeholder="选择审批人（多人时任一通过即生效）"
                style="width: 100%"
              />
            </div>
            <div class="approval-flow-item__foot">
              <span v-if="row.approverNames.length" class="approval-flow-item__names">
                当前审批人：{{ row.approverNames.join('、') }}
              </span>
              <a-button type="primary" size="small" :loading="flowSaving" @click="saveFlowRow(row)">
                保存
              </a-button>
            </div>
          </div>
        </div>
        <a-empty v-else description="暂无可配置的资源类型" />
      </a-spin>
    </a-modal>
  </div>
</template>

<style scoped lang="scss">
.approval-workbench {
  padding: 24px;
  min-height: 100%;
  background-color: #f5f6f8;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: #1a1a1a;
  font-size: 24px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  color: #666;
  font-size: 14px;
}

.page-content {
  padding: 16px 20px 20px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.approval-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 14px;
  border-bottom: 1px solid #f0f0f0;
}

.approval-tabs {
  display: flex;
  gap: 22px;
}

.approval-tab {
  height: 34px;
  padding: 0;
  color: #4b5563;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.approval-tab:hover {
  color: #1677ff;
}

.approval-tab.active {
  color: #1677ff;
  border-bottom-color: #1677ff;
  font-weight: 600;
}

.approval-filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.approval-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.approval-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  transition: all 0.2s ease;
}

.approval-item:hover {
  border-color: #1677ff;
  background-color: #fafcff;
}

.approval-item__main {
  min-width: 0;
  flex: 1;
}

.approval-item__title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  color: #1a1a1a;
  font-size: 15px;
}

.approval-item__type,
.approval-item__version {
  padding: 1px 7px;
  color: #64748b;
  border-radius: 4px;
  background: #f1f5f9;
  font-size: 12px;
}

.approval-item__meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  color: #666;
  font-size: 13px;
}

.approval-item__remark,
.approval-item__opinion {
  margin-top: 6px;
  color: #8c8c8c;
  font-size: 13px;
  line-height: 1.6;
}

.approval-item__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.approval-item__done {
  color: #9ca3af;
  font-size: 13px;
}

.approval-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.approval-modal__tip {
  margin: 0 0 12px;
  color: #666;
  font-size: 13px;
}

.approval-flow-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.approval-flow-item {
  padding: 14px 16px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}

.approval-flow-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 14px;
}

.approval-flow-item__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.approval-flow-item__names {
  color: #8c8c8c;
  font-size: 12px;
}
</style>
