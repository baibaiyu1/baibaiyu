/**
 * 智能体配置-日志子组件
 *
 * 双页签：会话记录（分页会话列表 + 对话详情）与任务日志（定时任务信息 + 执行记录）。
 *
 * @component
 */
<script setup lang="ts">
import { ref, onMounted, computed, nextTick } from 'vue'
import { LoadingOutlined, PushpinOutlined, DeleteOutlined, ClockCircleOutlined, HistoryOutlined } from '@ant-design/icons-vue'
import { Modal } from 'ant-design-vue'
import * as chatSessionApi from '@/api/chatSession'
import * as jobApi from '@/api/job'
import MessageList from '@/components/chatHistory/MessageList.vue'
import type { ChatSessionVO, ChatMessageVO, DisplayMessage, JobInfo } from '@/types'
import type { JobRecordVO } from '@/types/automation'
import dayjs from 'dayjs'

const props = defineProps<{
  agentId: string
}>()

/** 当前页签：会话记录 / 任务日志 */
const activeTab = ref<'session' | 'job'>('session')

const sessions = ref<ChatSessionVO[]>([])
const totalSessions = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const listLoading = ref(false)

const selectedSessionId = ref<string | null>(null)
const messages = ref<ChatMessageVO[]>([])
const detailLoading = ref(false)
const showDetail = ref(false)

/* ===================== 任务日志 ===================== */

const jobLoading = ref(false)
const jobLoaded = ref(false)
/** 当前智能体的全部定时任务（一个智能体可以绑定多个） */
const jobList = ref<JobInfo[]>([])
/** 正在查看执行记录的任务 */
const selectedJobId = ref<string>('')
const jobRecords = ref<JobRecordVO[]>([])
const jobRecordTotal = ref(0)

/** 当前选中的任务 */
const jobInfo = computed(() => jobList.value.find(job => String(job.id) === selectedJobId.value) || null)

/** 任务下拉选项（名称取 dataMap.jobName，缺失时回落到 Cron） */
const jobOptions = computed(() => jobList.value.map((job, index) => ({
  label: jobDisplayName(job, index),
  value: String(job.id)
})))

/**
 * 任务展示名
 * @param job 定时任务
 * @param index 序号（无名称时用于区分）
 */
function jobDisplayName(job: JobInfo, index: number): string {
  try {
    const dataMap = job.dataMap ? JSON.parse(job.dataMap) : {}
    return dataMap?.jobName || `定时任务 ${index + 1}`
  } catch {
    return `定时任务 ${index + 1}`
  }
}

/**
 * 加载当前智能体绑定的定时任务及其执行记录
 */
async function loadJob() {
  jobLoading.value = true
  try {
    const res = await jobApi.page({ page: 1, size: 100, type: 'AGENT', bizId: props.agentId })
    jobList.value = res.data.data?.records || []
    selectedJobId.value = jobList.value.length ? String(jobList.value[0]?.id) : ''
    await loadJobRecords()
    jobLoaded.value = true
  } catch (e) {
    console.error('加载任务日志失败:', e)
    jobList.value = []
    selectedJobId.value = ''
    jobRecords.value = []
    jobRecordTotal.value = 0
  } finally {
    jobLoading.value = false
  }
}

/**
 * 加载选中任务的执行记录
 */
async function loadJobRecords() {
  if (!selectedJobId.value) {
    jobRecords.value = []
    jobRecordTotal.value = 0
    return
  }
  const recordRes = await jobApi.records(selectedJobId.value, 1, 50)
  jobRecords.value = recordRes.data.data?.records || []
  jobRecordTotal.value = recordRes.data.data?.total || 0
}

/**
 * 切换要查看执行记录的任务
 * @param value 任务 ID
 */
function handleJobChange(value: string | number) {
  selectedJobId.value = String(value)
  void loadJobRecords()
}

/**
 * 任务输入内容（dataMap 中的 userPrompt / input 字段）
 */
const jobInput = computed(() => {
  const raw = jobInfo.value?.dataMap
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw)
    return parsed?.userPrompt || parsed?.input || raw
  } catch {
    return raw
  }
})

/**
 * 页签切换（任务日志首次进入时懒加载）
 */
function handleTabChange(key: string | number) {
  if (key === 'job' && !jobLoaded.value) {
    void loadJob()
  }
}

/**
 * 点击执行记录：跳转到对应会话
 */
async function handleOpenRecord(record: JobRecordVO) {
  activeTab.value = 'session'
  await nextTick()
  await selectSessionById(String(record.recordId))
}

/* ===================== 会话记录 ===================== */

/**
 * 加载会话列表
 */
async function loadSessions() {
  listLoading.value = true
  try {
    const res = await chatSessionApi.pageSessions({
      agentId: props.agentId,
      page: currentPage.value,
      size: pageSize.value
    })
    const page = res.data.data
    sessions.value = page.records || []
    totalSessions.value = page.total || 0
  } catch (e) {
    console.error('加载会话列表失败:', e)
  } finally {
    listLoading.value = false
  }
}

/**
 * 按会话ID加载对话详情
 */
async function selectSessionById(sessionId: string) {
  selectedSessionId.value = sessionId
  detailLoading.value = true
  showDetail.value = true
  try {
    const res = await chatSessionApi.getCurrentMessages(sessionId)
    messages.value = res.data.data || []
  } catch (e) {
    console.error('加载对话内容失败:', e)
    messages.value = []
  } finally {
    detailLoading.value = false
  }
}

/**
 * 选中会话，加载对话详情
 */
async function handleSelectSession(session: ChatSessionVO) {
  await selectSessionById(String(session.id))
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadSessions()
}

function formatTime(time: string) {
  if (!time) return ''
  return dayjs(time).format('MM-DD HH:mm')
}

/**
 * 删除会话（二次确认）
 */
function handleDeleteSession(session: ChatSessionVO, e: Event) {
  e.stopPropagation()
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除会话「${session.title || '未命名会话'}」吗？删除后不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await chatSessionApi.deleteSession(String(session.id))
        // 如果删除的是当前选中的会话，清空详情
        if (String(session.id) === selectedSessionId.value) {
          selectedSessionId.value = null
          messages.value = []
          showDetail.value = false
        }
        await loadSessions()
      } catch (err) {
        console.error('删除会话失败:', err)
      }
    }
  })
}

/**
 * 尝试从消息内容中解析推理和正文
 * 如果内容为 JSON 格式 {reasoning, content}，则提取两部分；否则原样返回
 */
function parseMessageContent(raw: string): { content: string; reasoningContent?: string } {
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && 'reasoning' in parsed && 'content' in parsed) {
      return { content: parsed.content as string, reasoningContent: parsed.reasoning as string }
    }
  } catch {
    // not JSON, return as-is
  }
  return { content: raw }
}


/**
 * 过滤掉 system 根消息
 */
const visibleMessages = computed(() =>{
  const list: DisplayMessage[] = []
  const messagesList = messages.value.filter(m => !(m.role === 'system' && m.depth === 0))

  for (let i = 0; i < messagesList.length; i++) {
    const m = messagesList[i]
    if (m == null) {
      continue
    }
    // 解析推理内容 - 不修改原数据
    const parsed = m.role === 'assistant'
      ? parseMessageContent(m.content || '')
      : { content: m.content || '', reasoningContent: undefined }

    list.push({
      id: String(m.id),
      role: m.role as DisplayMessage['role'],
      content: parsed.content,
      createdAt: m.createdAt,
      isStreaming: false
    })
  }

  return list
})

onMounted(() => loadSessions())
</script>

<template>
  <div class="history-container">
    <!-- 左侧：会话记录 / 任务日志 -->
    <div class="history-list-panel">
      <div class="history-list-inner">
        <ATabs v-model:activeKey="activeTab" size="small" class="history-tabs" @change="handleTabChange">
          <!-- 会话记录 -->
          <ATabPane key="session">
            <template #tab>
              <HistoryOutlined />
              <span style="margin-left: 6px;">会话记录</span>
              <ATag color="processing" style="margin-left: 6px;">{{ totalSessions }}</ATag>
            </template>

            <AppSpin :spinning="listLoading">
              <div v-if="sessions.length === 0 && !listLoading" style="padding: 40px 0; text-align: center;">
                <AEmpty description="暂无会话记录" />
              </div>

              <div v-else class="history-sessions">
                <div
                  v-for="session in sessions"
                  :key="session.id"
                  class="history-session-item"
                  :class="{ active: String(session.id) === selectedSessionId }"
                  @click="handleSelectSession(session)"
                >
                  <div class="session-title-row">
                    <span class="session-title-text">
                      <PushpinOutlined v-if="session.isPinned" style="margin-right: 6px; color: #4449d0;" />
                      {{ session.title || '未命名会话' }}
                    </span>
                    <DeleteOutlined
                      class="session-delete-btn"
                      @click="handleDeleteSession(session, $event)"
                    />
                  </div>
                  <div class="session-meta">
                    <span>创建于 {{ formatTime(session.createdAt) }}</span>
                    <span style="margin-left: 12px;">更新于 {{ formatTime(session.updatedAt) }}</span>
                  </div>
                </div>
              </div>
            </AppSpin>

            <div v-if="totalSessions > pageSize" style="padding: 16px 0; display: flex; justify-content: center;">
              <APagination
                :current="currentPage"
                :total="totalSessions"
                :page-size="pageSize"
                size="small"
                @change="handlePageChange"
              />
            </div>
          </ATabPane>

          <!-- 任务日志 -->
          <ATabPane key="job">
            <template #tab>
              <ClockCircleOutlined />
              <span style="margin-left: 6px;">任务日志</span>
            </template>

            <AppSpin :spinning="jobLoading">
              <div v-if="!jobList.length" class="job-empty">
                <AEmpty description="该智能体暂未绑定定时任务" />
                <p class="job-empty-tip">可在左侧「定时」中为智能体创建定时任务</p>
              </div>

              <div v-else class="job-panel">
                <!-- 一个智能体可绑定多个定时任务，这里选择要查看执行记录的那个 -->
                <div v-if="jobList.length > 1" class="job-picker">
                  <span class="job-info-label">定时任务</span>
                  <ASelect
                    :value="selectedJobId"
                    :options="jobOptions"
                    size="small"
                    style="min-width: 220px"
                    @change="handleJobChange"
                  />
                </div>

                <div v-if="jobInfo" class="job-info">
                  <div class="job-info-row">
                    <span class="job-info-label">触发周期</span>
                    <code class="job-info-value">{{ jobInfo.cron }}</code>
                  </div>
                  <div class="job-info-row">
                    <span class="job-info-label">任务状态</span>
                    <ATag :color="jobInfo.enabled ? 'green' : 'default'">
                      {{ jobInfo.enabled ? '运行中' : '已停止' }}
                    </ATag>
                  </div>
                  <div v-if="jobInput" class="job-info-row">
                    <span class="job-info-label">任务输入</span>
                    <span class="job-info-value job-info-input" :title="jobInput">{{ jobInput }}</span>
                  </div>
                </div>

                <div class="job-records-head">
                  <span>执行记录</span>
                  <ATag color="processing">{{ jobRecordTotal }}</ATag>
                </div>

                <div v-if="jobRecords.length === 0" style="padding: 24px 0; text-align: center;">
                  <AEmpty description="暂无执行记录" />
                </div>

                <div v-else class="job-records">
                  <div
                    v-for="record in jobRecords"
                    :key="String(record.recordId)"
                    class="job-record-item"
                    @click="handleOpenRecord(record)"
                  >
                    <ClockCircleOutlined class="job-record-icon" />
                    <span class="job-record-time">{{ record.createTime }}</span>
                    <span class="job-record-link">查看会话</span>
                  </div>
                </div>
              </div>
            </AppSpin>
          </ATabPane>
        </ATabs>
      </div>
    </div>

    <!-- 对话详情 -->
    <div class="history-detail-panel">
      <div v-if="detailLoading" class="loading-messages">
        <LoadingOutlined style="margin-right: 6px" />加载中
      </div>
      <div v-else-if="visibleMessages.length === 0" style="text-align: center;margin-top: 250px">
        <AEmpty description="暂无对话内容" />
      </div>
      <MessageList v-else :messages="visibleMessages as DisplayMessage[]"/>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/agent/config-panel.scss' as *;
.loading-messages {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
}

.history-tabs {
  height: 100%;

  :deep(.ant-tabs-nav) {
    margin-bottom: 8px;
  }
}

.job-panel {
  padding: 4px 2px;
}

/* 多定时任务时的任务选择器 */
.job-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12px;
}

.job-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fafcff;
}

.job-info-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.job-info-label {
  flex: 0 0 56px;
  color: #8c98a8;
}

.job-info-value {
  color: #2b3445;
  word-break: break-all;
}

.job-info-input {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.job-records-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 0 6px;
  color: #2b3445;
  font-size: 13px;
  font-weight: 600;
}

.job-records {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.job-record-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.2s ease;

  &:hover {
    background: #f2f6ff;
  }
}

.job-record-icon {
  color: #8c98a8;
}

.job-record-time {
  flex: 1;
  color: #2b3445;
}

.job-record-link {
  color: #4449d0;
}

.job-empty {
  padding: 32px 0;
  text-align: center;
}

.job-empty-tip {
  margin: 6px 0 0;
  color: #98a4b8;
  font-size: 12px;
}
</style>
