<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 会话文件管理
 *
 * 按会话维度管理智能体工作空间里的文件：列表展示每个会话的占用空间与使用率，
 * 「查看文件」打开弹窗，在文件树里预览 / 上传 / 下载 / 删除 / 清空。
 *
 * 用量口径与「存储管理 → 会话工作空间用量」完全一致：由 File Runner 按会话目录实时统计
 * （`GET /runtime/workspace/capacity`）。该接口没有批量版本，因此只对**当前页**的会话并发查询
 * （单页 10 条，成本可接受）；改文件后只重查本页容量，不整表重载。
 *
 * 「会话来源」筛选里的**游客**是分享 chatKey 链接产生的会话：其归属人是链接每次访问现场生成的
 * 随机 userId，不在账号表里，所以既不会出现在任何登录用户的列表里，也没有常规清理入口，
 * 工作空间目录会长期占盘。该筛选与清理动作都要求 `agent:chat:guest:manage` 权限点
 * （V21 迁移种的，默认只给平台管理员），普通用户既看不到入口、也调不动接口。
 *
 */
import { computed, onMounted, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import {
  EllipsisOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  RobotOutlined
} from '@ant-design/icons-vue'
import SessionFileBrowserModal from '@/components/session/SessionFileBrowserModal.vue'
import * as chatSessionApi from '@/api/chatSession'
import * as workspaceApi from '@/api/workspace'
import * as agentApi from '@/api/agent'
import { useAccountStore } from '@/stores'
import type { ChatSessionVO, WorkspaceCapacityVO } from '@/types'
import dayjs from 'dayjs'

const props = defineProps<{
  /** 在设置页的存储管理标签中嵌入时隐藏独立页面头 */
  embedded?: boolean
}>()

const isEmbedded = computed(() => props.embedded === true)

/** 游客会话管理权限点（与 V21 迁移里的 menu id 3112 一致） */
const PERM_GUEST_MANAGE = 'agent:chat:guest:manage'

/** 表格行：会话 + 智能体名 + 工作空间用量 */
interface SessionFileRow {
  session: ChatSessionVO
  /** 智能体名称，缺失时回落为 ID */
  agentName: string
  capacity: WorkspaceCapacityVO | null
}

/** AMenu 点击事件（与 SessionItem 保持同一写法） */
interface MenuClickEvent {
  key: string
}

/** 无数据时的容量兜底（目录不存在等场景按 0 展示） */
const EMPTY_CAPACITY: WorkspaceCapacityVO = {
  usedBytes: 0,
  maxBytes: 0,
  usedReadable: '0 B',
  maxReadable: '-',
  percent: 0
}

const loading = ref(false)
/** 当前页会话 */
const sessions = ref<ChatSessionVO[]>([])
/** 会话ID -> 工作空间容量 */
const capacityMap = ref<Record<string, WorkspaceCapacityVO>>({})
/** 会话ID -> 智能体名 */
const agentNameMap = ref<Record<string, string>>({})
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
/** 智能体筛选（空为全部） */
const agentFilter = ref<string | undefined>(undefined)
/** 智能体下拉选项 */
const agentOptions = ref<Array<{ label: string; value: string }>>([])
/**
 * 会话来源筛选：undefined=本人，'guest'=游客
 *
 * 不用布尔值是为了让 ASelect 的 allow-clear 语义自然：清空即回到「本人」。
 */
const sourceFilter = ref<'guest' | undefined>(undefined)
const sessionScope = ref<'mine' | 'team' | 'all'>('mine')
const sessionAccess = ref<chatSessionApi.SessionAccessVO>({ canViewTeam: false, canViewAll: false })

/** 文件浏览器弹窗 */
const browserOpen = ref(false)
const browserSession = ref<ChatSessionVO | null>(null)

const { hasPerm } = useAccountStore()

/** 是否可管理游客会话（决定来源筛选项与清理入口是否出现） */
const canManageGuest = computed(() => hasPerm(PERM_GUEST_MANAGE))

/**
 * 会话来源下拉选项
 *
 * 无权限时返回空数组，把「游客」这个选项整体藏掉 —— 后端也会拒，
 * 但不给用户一个点了就报错的入口。
 */
const sourceOptions = computed(() =>
  canManageGuest.value ? [{ label: '游客会话', value: 'guest' }] : []
)

const scopeOptions = computed(() => [
  { label: '我的会话', value: 'mine' },
  ...(sessionAccess.value.canViewTeam ? [{ label: '负责部门', value: 'team' }] : []),
  ...(sessionAccess.value.canViewAll ? [{ label: '全部会话', value: 'all' }] : [])
])

const hasData = computed(() => sessions.value.length > 0)

/**
 * 表格数据行
 *
 * 会话 / 容量 / 智能体名三份数据异步到达（分页接口、逐会话容量、智能体下拉），
 * 用 computed 合并而不是往会话对象上挂字段，避免污染接口原始数据。
 */
const rows = computed<SessionFileRow[]>(() =>
  sessions.value.map((session) => {
    const id = String(session.id)
    const agentId = session.agentId ? String(session.agentId) : ''
    return {
      session,
      agentName: agentId ? agentNameMap.value[agentId] || agentId : '-',
      capacity: capacityMap.value[id] ?? null
    }
  })
)

/**
 * 加载智能体下拉选项，同时建立 ID → 名称 映射
 */
async function loadAgents() {
  try {
    const res = await agentApi.page({ page: 1, size: 100 })
    const records = res.data.data?.records || []
    const options: Array<{ label: string; value: string }> = []
    const nameMap: Record<string, string> = {}
    for (const item of records) {
      const id = String(item.id)
      options.push({ label: item.name, value: id })
      nameMap[id] = item.name
    }
    agentOptions.value = options
    agentNameMap.value = nameMap
  } catch (e) {
    console.warn('[会话文件] 加载智能体列表失败', e)
  }
}

/**
 * 查询一组会话的工作空间容量
 *
 * @param list 会话列表（通常是当前页）
 */
async function fetchCapacities(list: ChatSessionVO[]) {
  const result: Record<string, WorkspaceCapacityVO> = {}
  await Promise.all(
    list.map(async (session) => {
      const id = String(session.id)
      try {
        const res = await workspaceApi.getCapacity(id)
        if (res.data?.data) {
          result[id] = res.data.data
        }
      } catch {
        // 工作空间目录尚未创建（从未产生文件）时接口会失败，按 0 处理，不阻塞整表
      }
    })
  )
  capacityMap.value = result
}

/**
 * 加载当前页会话与用量
 */
async function loadSessions() {
  loading.value = true
  try {
    const res = await chatSessionApi.pageSessions({
      page: currentPage.value,
      size: pageSize.value,
      agentId: agentFilter.value,
      scope: sessionScope.value,
      // 仅在选中「游客会话」时下发，避免给常规请求带上无意义的 guestOnly=false
      guestOnly: sourceFilter.value === 'guest' ? true : undefined
    })
    const page = res.data.data
    sessions.value = page?.records || []
    total.value = page?.total || 0
    await fetchCapacities(sessions.value)
  } catch (e) {
    console.error('[会话文件] 加载会话列表失败', e)
  } finally {
    loading.value = false
  }
}

/**
 * 只重查当前页容量（弹窗内改过文件后调用）
 */
async function refreshCapacities() {
  await fetchCapacities(sessions.value)
}

/**
 * 刷新列表并回到第一页
 *
 * 已经在第一页时 currentPage 不会变化、下面的 watch 不会触发（筛选条件改了但页码没变），
 * 所以这里要显式补一次请求；否则交给 watch 走，避免同一动作打两遍接口。
 */
function reload() {
  if (currentPage.value === 1) {
    void loadSessions()
  } else {
    currentPage.value = 1
  }
}

/**
 * 批量清理游客会话
 *
 * 范围跟随顶部筛选：选定了智能体就只清该智能体的游客会话。
 * 后端会连带删除工作空间目录，因此这里必须二次确认并写明当前范围。
 */
function handleClearGuestSessions() {
  const scopedAgentId = agentFilter.value
  const scopeText = scopedAgentId
    ? `智能体「${agentNameMap.value[scopedAgentId] || scopedAgentId}」下`
    : '当前组织下'
  Modal.confirm({
    title: '清理游客会话',
    content: `将删除${scopeText}的全部游客会话，并连带删除它们的工作空间文件。该操作不可恢复，是否继续？`,
    okText: '确认清理',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const res = await chatSessionApi.clearGuestSessions(scopedAgentId)
        const deleted = res.data.data?.deleted ?? 0
        message.success(`已清理 ${deleted} 个游客会话`)
        await loadSessions()
      } catch (e) {
        console.error('[会话文件] 清理游客会话失败', e)
      }
    }
  })
}

// 翻页与改每页条数都只经由这里重查：把请求收敛到一处，
// 避免 @change 与 @show-size-change 同时触发导致一次操作打两遍接口
watch([currentPage, pageSize], () => {
  void loadSessions()
})

/** 容量信息（无数据按 0 展示） */
function capacityOf(row: SessionFileRow): WorkspaceCapacityVO {
  return row.capacity ?? EMPTY_CAPACITY
}

/**
 * 存储空间文案：有用量时「已用 / 上限」，完全没有文件时显示占位而不是「0 B / -」
 */
function storageText(row: SessionFileRow): string {
  const capacity = capacityOf(row)
  if (!capacity.usedBytes && !capacity.maxBytes) return '-'
  return `${capacity.usedReadable} / ${capacity.maxReadable}`
}

/**
 * 使用状态：正常 / 接近上限 / 已超限
 */
function usageStatus(row: SessionFileRow) {
  const percent = capacityOf(row).percent || 0
  if (percent >= 100) return { text: '已超限', color: 'red' }
  if (percent >= 80) return { text: '接近上限', color: 'orange' }
  return { text: '正常', color: 'green' }
}

/**
 * 进度条宽度（夹在 0~100）
 *
 * 有用量但占比极小时（如 5KB / 1GB = 0.0%）真实的百分比宽度渲染出来是「空条」，
 * 与 0 无法区分，因此给非零用量一个 2% 的最小可见宽度。
 */
function percentWidth(row: SessionFileRow): number {
  const percent = capacityOf(row).percent || 0
  if (percent <= 0) return 0
  return Math.min(Math.max(percent, 2), 100)
}

/** 使用率展示：整数不带小数位，避免「0.0%」的噪声 */
function percentText(row: SessionFileRow): string {
  const percent = capacityOf(row).percent || 0
  return `${percent % 1 === 0 ? percent : percent.toFixed(1)}%`
}

function formatTime(time?: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

/**
 * 打开文件浏览器
 */
function handleViewFiles(row: SessionFileRow) {
  browserSession.value = row.session
  browserOpen.value = true
}

/**
 * 清空该会话的对话记录（消息清空，会话与工作空间保留）
 */
function handleClearMessages(row: SessionFileRow) {
  const id = String(row.session.id)
  Modal.confirm({
    title: '清空对话记录',
    content: `确定清空会话「${row.session.title || '未命名会话'}」的对话记录吗？会话与工作空间文件将保留，此操作不可恢复。`,
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await chatSessionApi.clearSessionMessages(id)
        message.success('已清空对话记录')
      } catch (e) {
        console.error('[会话文件] 清空对话记录失败', e)
      }
    }
  })
}

/**
 * 清空该会话的工作空间文件（会话与对话记录保留）
 */
function handleClearWorkspace(row: SessionFileRow) {
  const id = String(row.session.id)
  Modal.confirm({
    title: '清空工作空间',
    content: `确定清空会话「${row.session.title || '未命名会话'}」的工作空间文件吗？该操作不可恢复。`,
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await workspaceApi.clearWorkspace(id)
        message.success('已清空该会话的工作空间')
        await refreshCapacities()
      } catch (e) {
        console.error('[会话文件] 清空工作空间失败', e)
      }
    }
  })
}

/**
 * 删除该游客会话（连带消息、轨迹与工作空间目录）
 */
function handleDeleteGuest(row: SessionFileRow) {
  const id = String(row.session.id)
  Modal.confirm({
    title: '删除游客会话',
    content: `确定删除游客会话「${row.session.title || '未命名会话'}」吗？其消息与工作空间文件会一并删除，此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await chatSessionApi.deleteGuestSession(id)
        message.success('已删除该游客会话')
        await loadSessions()
      } catch (e) {
        console.error('[会话文件] 删除游客会话失败', e)
      }
    }
  })
}

/**
 * 行内「更多」菜单分发
 *
 * @param row 当前行
 * @param key 菜单项 key
 */
function handleRowAction(row: SessionFileRow, key: string) {
  if (key === 'messages') {
    handleClearMessages(row)
  } else if (key === 'workspace') {
    handleClearWorkspace(row)
  } else if (key === 'deleteGuest') {
    handleDeleteGuest(row)
  }
}

onMounted(() => {
  void chatSessionApi.access().then(res => {
    sessionAccess.value = res.data.data ?? { canViewTeam: false, canViewAll: false }
  }).catch(error => console.warn('[会话文件] 查询管理范围失败', error))
  void loadAgents()
  void loadSessions()
})
</script>

<template>
  <div :class="{ 'session-file-page': !isEmbedded, 'session-file-embedded': isEmbedded }">
    <!-- 页头 -->
    <div class="page-header">
      <div v-if="!isEmbedded">
        <h1 class="page-title">会话文件管理</h1>
        <p class="page-desc">按会话查看与管理智能体工作空间中的文件</p>
      </div>
      <div class="page-actions">
        <ASelect
          v-if="sessionAccess.canViewTeam || sessionAccess.canViewAll"
          v-model:value="sessionScope"
          :options="scopeOptions"
          style="width: 148px"
          @change="reload"
        />
        <ASelect
          v-if="canManageGuest"
          v-model:value="sourceFilter"
          :options="sourceOptions"
          placeholder="会话来源：本人"
          allow-clear
          style="width: 176px"
          @change="reload"
        />
        <ASelect
          v-model:value="agentFilter"
          :options="agentOptions"
          placeholder="全部智能体"
          allow-clear
          show-search
          option-filter-prop="label"
          style="width: 200px"
          @change="reload"
        />
        <AButton v-if="canManageGuest" danger @click="handleClearGuestSessions">
          清理游客会话
        </AButton>
        <AButton :loading="loading" @click="loadSessions">
          <template #icon><ReloadOutlined /></template>
          刷新
        </AButton>
      </div>
    </div>

    <!-- 会话表格 -->
    <div class="page-content">
      <ASpin :spinning="loading">
        <div v-if="!hasData && !loading" class="table-empty">
          {{ sourceFilter === 'guest' ? '当前范围下没有游客会话' : '暂无会话数据' }}
        </div>
        <table v-else class="session-table">
          <thead>
            <tr>
              <th>会话标题</th>
              <th style="width: 160px;">智能体</th>
              <th style="width: 170px;">存储空间</th>
              <th style="width: 190px;">使用率</th>
              <th style="width: 110px;">状态</th>
              <th style="width: 160px;">更新时间</th>
              <th style="width: 150px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="String(row.session.id)">
              <td>
                <span class="session-title" :title="row.session.title || ''">
                  <FileTextOutlined class="session-title-icon" />
                  <span class="session-title-text">{{ row.session.title || '未命名会话' }}</span>
                  <ATag v-if="row.session.isGuest" color="purple" class="session-guest-tag">游客</ATag>
                </span>
              </td>
              <td>
                <span class="agent-cell" :title="row.agentName">
                  <RobotOutlined class="agent-cell-icon" />
                  {{ row.agentName }}
                </span>
              </td>
              <td>{{ storageText(row) }}</td>
              <td>
                <div class="usage-bar">
                  <span class="usage-bar-track">
                    <i :style="{ width: `${percentWidth(row)}%` }" />
                  </span>
                  <span class="usage-bar-value">{{ percentText(row) }}</span>
                </div>
              </td>
              <td>
                <ATag :color="usageStatus(row).color">{{ usageStatus(row).text }}</ATag>
              </td>
              <td>{{ formatTime(row.session.updatedAt) }}</td>
              <td>
                <div class="row-actions">
                  <AButton size="small" type="link" @click="handleViewFiles(row)">
                    <template #icon><FolderOpenOutlined /></template>
                    查看文件
                  </AButton>
                  <ADropdown
                    v-if="row.session.canWrite || (row.session.isGuest && canManageGuest)"
                    :trigger="['click']"
                    placement="bottomRight"
                  >
                    <AButton size="small" type="link" @click.stop>
                      <EllipsisOutlined />
                    </AButton>
                    <template #overlay>
                      <AMenu @click="({ key }: MenuClickEvent) => handleRowAction(row, key as string)">
                        <AMenuItem v-if="row.session.canWrite && !row.session.isGuest" key="messages">清空对话记录</AMenuItem>
                        <AMenuItem v-if="row.session.canWrite" key="workspace" danger>清空工作空间</AMenuItem>
                        <AMenuItem
                          v-if="row.session.isGuest && canManageGuest"
                          key="deleteGuest"
                          danger
                        >
                          删除游客会话
                        </AMenuItem>
                      </AMenu>
                    </template>
                  </ADropdown>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </ASpin>

      <div v-if="total > 0" class="table-pagination">
        <APagination
          v-model:current="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          size="small"
          show-size-changer
          :page-size-options="['10', '15', '20', '50']"
          :show-total="(t: number) => `共 ${t} 条`"
        />
      </div>
    </div>

    <!-- 文件浏览器弹窗 -->
    <SessionFileBrowserModal
      v-if="browserSession"
      v-model:open="browserOpen"
      :session-id="String(browserSession.id)"
      :session-title="browserSession.title || '未命名会话'"
      :agent-name="agentNameMap[String(browserSession.agentId)] || String(browserSession.agentId)"
      :can-write="browserSession.canWrite === true"
      @changed="refreshCapacities"
    />
  </div>
</template>

<style scoped lang="scss">
.session-file-page {
  padding: 24px;
  background-color: #f5f6f8;
  min-height: 100%;
}

.session-file-embedded {
  min-width: 0;

  .page-header {
    justify-content: flex-end;
    margin-bottom: 16px;
  }

  .page-actions {
    min-width: 0;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .page-content {
    padding: 0;
    border-radius: 0;
    box-shadow: none;
    overflow-x: auto;
  }

  .session-table {
    min-width: 960px;
  }
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 8px 0;
  color: #1a1a1a;
  font-size: 24px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  color: #666;
  font-size: 14px;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.page-content {
  padding: 4px 20px 20px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.table-empty {
  padding: 48px 0;
  color: #98a4b8;
  font-size: 13px;
  text-align: center;
}

.session-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  table-layout: fixed;

  th {
    height: 46px;
    padding: 0 12px;
    color: #6b7280;
    border-bottom: 1px solid #eef1f6;
    background: #fff;
    font-weight: 600;
    text-align: left;
    white-space: nowrap;
  }

  td {
    height: 52px;
    padding: 0 12px;
    overflow: hidden;
    color: #2b3445;
    border-bottom: 1px solid #f4f6fa;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  tbody tr:hover td {
    background: #fafbfd;
  }
}

.session-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-title-icon {
  flex-shrink: 0;
  color: #7c8db5;
}

/* 省略只作用于标题文本，游客标签不被一起截掉 */
.session-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 游客会话标记：标题可能被截断，标签本身不参与省略 */
.session-guest-tag {
  flex-shrink: 0;
  margin-inline-end: 0;
  transform: scale(0.85);
}

.agent-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-cell-icon {
  flex-shrink: 0;
  color: #98a4b8;
}

.usage-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.usage-bar-track {
  position: relative;
  flex: 1;
  height: 6px;
  overflow: hidden;
  border-radius: 3px;
  background: #eef1f6;

  i {
    position: absolute;
    top: 0;
    left: 0;
    height: 100%;
    border-radius: 3px;
    background: #4449d0;
  }
}

.usage-bar-value {
  flex: 0 0 46px;
  color: #6b7280;
  font-size: 12px;
  text-align: right;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
