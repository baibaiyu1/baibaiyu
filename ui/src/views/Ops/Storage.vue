<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 存储管理页面
 *
 * 存储配置（存储协议）+ 会话工作空间用量管理（按会话查看用量、使用率并支持清理）。
 *
 */
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { ClearOutlined, FolderOpenOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import StorageManagement from '@/components/settings/StorageManagement.vue'
import * as chatSessionApi from '@/api/chatSession'
import * as workspaceApi from '@/api/workspace'
import * as agentApi from '@/api/agent'
import type { ChatSessionVO, WorkspaceCapacityVO } from '@/types'
import dayjs from 'dayjs'

/* ===================== 会话工作空间用量 ===================== */

const loading = ref(false)
const sessions = ref<ChatSessionVO[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const agentFilter = ref<string | undefined>(undefined)
const agents = ref<Array<{ label: string; value: string }>>([])
/** 会话ID -> 容量信息 */
const capacityMap = ref<Record<string, WorkspaceCapacityVO>>({})
const clearingId = ref<string | null>(null)

/**
 * 加载智能体下拉选项
 */
async function loadAgents() {
  try {
    const res = await agentApi.page({ page: 1, size: 100 })
    const records = res.data.data?.records || []
    agents.value = records.map((item) => ({ label: item.name, value: String(item.id) }))
  } catch (e) {
    console.warn('[存储] 加载智能体列表失败', e)
  }
}

/**
 * 加载会话列表并逐个查询工作空间容量
 *
 * 说明：容量由 File Runner 按会话目录实时计算，暂无批量接口，
 * 因此仅对当前页的会话并发查询（单页 10 条，成本可接受）。
 */
async function loadSessions() {
  loading.value = true
  try {
    const res = await chatSessionApi.pageSessions({
      page: currentPage.value,
      size: pageSize.value,
      agentId: agentFilter.value
    })
    const page = res.data.data
    sessions.value = page?.records || []
    total.value = page?.total || 0

    const result: Record<string, WorkspaceCapacityVO> = {}
    await Promise.all(
      sessions.value.map(async (session) => {
        const id = String(session.id)
        try {
          const capacityRes = await workspaceApi.getCapacity(id)
          if (capacityRes.data?.data) {
            result[id] = capacityRes.data.data
          }
        } catch (e) {
          // 目录不存在等场景按 0 处理，不阻塞整表渲染
          console.warn('[存储] 查询会话工作空间容量失败', id, e)
        }
      })
    )
    capacityMap.value = result
  } catch (e) {
    console.error('[存储] 加载会话列表失败', e)
  } finally {
    loading.value = false
  }
}

function reload() {
  currentPage.value = 1
  void loadSessions()
}

/**
 * 容量信息（无数据时按 0 展示）
 */
function capacityOf(session: ChatSessionVO): WorkspaceCapacityVO {
  return (
    capacityMap.value[String(session.id)] || {
      usedBytes: 0,
      maxBytes: 0,
      usedReadable: '0 B',
      maxReadable: '-',
      percent: 0
    }
  )
}

/**
 * 使用状态：正常 / 接近上限 / 已超限
 */
function usageStatus(session: ChatSessionVO) {
  const percent = capacityOf(session).percent || 0
  if (percent >= 100) return { text: '已超限', color: 'red' }
  if (percent >= 80) return { text: '接近上限', color: 'orange' }
  return { text: '正常', color: 'green' }
}

/** 智能体名称 */
function agentName(session: ChatSessionVO) {
  const id = session.agentId ? String(session.agentId) : ''
  return agents.value.find((item) => item.value === id)?.label || id || '-'
}

function formatTime(time?: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

/**
 * 清理会话工作空间（二次确认）
 */
function handleClear(session: ChatSessionVO) {
  const id = String(session.id)
  Modal.confirm({
    title: '清理工作空间',
    content: `确定清空会话「${session.title || '未命名会话'}」的工作空间文件吗？该操作不可恢复。`,
    okText: '清空',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      clearingId.value = id
      try {
        await workspaceApi.clearWorkspace(id)
        message.success('已清空该会话的工作空间')
        await loadSessions()
      } catch (e) {
        console.error('[存储] 清理工作空间失败', e)
      } finally {
        clearingId.value = null
      }
    }
  })
}

const hasData = computed(() => sessions.value.length > 0)

onMounted(() => {
  void loadAgents()
  void loadSessions()
})
</script>

<template>
  <div class="ops-page">
    <div class="page-header">
      <h1 class="page-title">存储管理</h1>
      <p class="page-desc">管理系统存储配置与会话工作空间用量</p>
    </div>

    <div class="page-content">
      <StorageManagement :show-session-files="false" />
    </div>

    <div class="page-content usage-section">
      <div class="usage-head">
        <div>
          <span class="usage-title">会话工作空间用量</span>
          <span class="usage-desc">每个会话独立的文件空间，供智能体读写与产出文件</span>
        </div>
        <div class="usage-filters">
          <ASelect
            v-model:value="agentFilter"
            :options="agents"
            placeholder="全部智能体"
            allow-clear
            show-search
            option-filter-prop="label"
            style="width: 200px"
            @change="reload"
          />
          <AButton :loading="loading" @click="loadSessions">
            <template #icon><ReloadOutlined /></template>
            刷新
          </AButton>
        </div>
      </div>

      <ASpin :spinning="loading">
        <div v-if="!hasData && !loading" class="usage-empty">暂无会话数据</div>
        <table v-else class="usage-table">
          <thead>
            <tr>
              <th>会话标题</th>
              <th style="width: 160px;">智能体</th>
              <th style="width: 150px;">存储用量</th>
              <th style="width: 180px;">使用率</th>
              <th style="width: 110px;">状态</th>
              <th style="width: 160px;">更新时间</th>
              <th style="width: 100px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="session in sessions" :key="String(session.id)">
              <td>
                <span class="usage-session-title" :title="session.title || ''">
                  <FolderOpenOutlined />
                  {{ session.title || '未命名会话' }}
                </span>
              </td>
              <td>{{ agentName(session) }}</td>
              <td>{{ capacityOf(session).usedReadable }} / {{ capacityOf(session).maxReadable }}</td>
              <td>
                <div class="usage-bar">
                  <span class="usage-bar-track">
                    <i :style="{ width: `${Math.min(capacityOf(session).percent || 0, 100)}%` }" />
                  </span>
                  <span class="usage-bar-value">{{ (capacityOf(session).percent || 0).toFixed(1) }}%</span>
                </div>
              </td>
              <td>
                <ATag :color="usageStatus(session).color">{{ usageStatus(session).text }}</ATag>
              </td>
              <td>{{ formatTime(session.updatedAt) }}</td>
              <td>
                <AButton
                  size="small"
                  danger
                  :loading="clearingId === String(session.id)"
                  @click="handleClear(session)"
                >
                  <template #icon><ClearOutlined /></template>
                  清理
                </AButton>
              </td>
            </tr>
          </tbody>
        </table>
      </ASpin>

      <div v-if="total > pageSize" class="usage-pagination">
        <APagination
          v-model:current="currentPage"
          :total="total"
          :page-size="pageSize"
          size="small"
          show-size-changer
          @change="loadSessions"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.ops-page {
  padding: 24px;
  background-color: #F5F6F8;
  min-height: 100%;
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 0 0 8px 0;
}

.page-desc {
  margin: 0;
  color: #666;
  font-size: 14px;
}

.page-content {
  padding: 20px 24px 24px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.usage-section {
  margin-top: 18px;
}

.usage-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.usage-title {
  display: block;
  color: #1f2737;
  font-size: 15px;
  font-weight: 600;
}

.usage-desc {
  display: block;
  margin-top: 4px;
  color: #98a4b8;
  font-size: 12px;
}

.usage-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.usage-empty {
  padding: 32px 0;
  color: #98a4b8;
  font-size: 13px;
  text-align: center;
}

.usage-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  table-layout: fixed;

  th {
    height: 42px;
    padding: 0 12px;
    color: #6b7280;
    border-bottom: 1px solid #eef1f6;
    background: #fafbfd;
    font-weight: 600;
    text-align: left;
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
}

.usage-session-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.usage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
