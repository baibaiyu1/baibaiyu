<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 服务监控页面
 *
 * 运行时总览（会话 / 定时任务 / 执行节点 / 最近任务执行）+ 执行节点明细。
 *
 */
import { computed, onMounted, ref } from 'vue'
import {
  ClusterOutlined,
  ClockCircleOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  ReloadOutlined
} from '@ant-design/icons-vue'
import ExecutionNodes from '@/components/settings/ExecutionNodes.vue'
import * as chatSessionApi from '@/api/chatSession'
import * as jobApi from '@/api/job'
import * as heartbeatApi from '@/api/heartbeat'
import type { JobInfo, NodeStatusVO } from '@/types'
import type { JobRecordVO } from '@/types/automation'
import dayjs from 'dayjs'

const loading = ref(false)
const sessionTotal = ref(0)
const jobs = ref<JobInfo[]>([])
const nodes = ref<NodeStatusVO[]>([])
/** 最近执行记录（跨任务合并，倒序，最多展示 10 条） */
const recentRecords = ref<Array<JobRecordVO & { jobType?: string }>>([])

const enabledJobCount = computed(() => jobs.value.filter((item) => item.enabled).length)
const onlineNodeCount = computed(() => nodes.value.filter((item) => item.nodeStatus === 'HEALTHY').length)
const latestRecordTime = computed(() => {
  const first = recentRecords.value[0]
  return first?.createTime ? dayjs(first.createTime).format('MM-DD HH:mm') : '—'
})

/**
 * 加载运行时总览数据
 */
async function loadOverview() {
  loading.value = true
  try {
    const [sessionRes, jobRes, nodeRes] = await Promise.all([
      chatSessionApi.pageSessions({ page: 1, size: 1 }),
      jobApi.list(),
      heartbeatApi.listNodes()
    ])
    sessionTotal.value = sessionRes.data.data?.total || 0
    jobs.value = jobRes.data.data || []
    nodes.value = nodeRes.data.data || []

    // 最近执行记录：取前 5 个已启用任务的记录合并（避免任务很多时产生过多请求）
    const enabledJobs = jobs.value.filter((item) => item.enabled).slice(0, 5)
    const recordGroups = await Promise.all(
      enabledJobs.map(async (job) => {
        try {
          const res = await jobApi.records(String(job.id), 1, 5)
          return (res.data.data?.records || []).map((record) => ({ ...record, jobType: job.type }))
        } catch (e) {
          console.error('加载任务执行记录失败:', e)
          return []
        }
      })
    )
    recentRecords.value = recordGroups
      .flat()
      .sort((a, b) => String(b.createTime).localeCompare(String(a.createTime)))
      .slice(0, 10)
  } catch (e) {
    console.error('加载运行时总览失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadOverview)
</script>

<template>
  <div class="ops-page">
    <div class="page-header">
      <h1 class="page-title">服务监控</h1>
      <p class="page-desc">监控系统服务运行状态与任务执行情况</p>
    </div>

    <div class="page-content">
      <div class="overview-head">
        <span class="overview-title">运行时总览</span>
        <AButton size="small" :loading="loading" @click="loadOverview">
          <template #icon><ReloadOutlined /></template>
          刷新
        </AButton>
      </div>

      <div class="overview-grid">
        <div class="overview-card">
          <span class="overview-icon icon-session"><MessageOutlined /></span>
          <div>
            <div class="overview-label">会话总数</div>
            <div class="overview-value">{{ sessionTotal }}</div>
          </div>
        </div>

        <div class="overview-card">
          <span class="overview-icon icon-job"><ClockCircleOutlined /></span>
          <div>
            <div class="overview-label">定时任务</div>
            <div class="overview-value">{{ enabledJobCount }}<small> / {{ jobs.length }} 启用</small></div>
          </div>
        </div>

        <div class="overview-card">
          <span class="overview-icon icon-node"><ClusterOutlined /></span>
          <div>
            <div class="overview-label">执行节点</div>
            <div class="overview-value">{{ onlineNodeCount }}<small> / {{ nodes.length }} 在线</small></div>
          </div>
        </div>

        <div class="overview-card">
          <span class="overview-icon icon-run"><PlayCircleOutlined /></span>
          <div>
            <div class="overview-label">最近任务执行</div>
            <div class="overview-value">{{ latestRecordTime }}</div>
          </div>
        </div>
      </div>

      <div class="overview-records">
        <div class="overview-records-head">最近执行记录</div>
        <div v-if="recentRecords.length === 0" class="overview-records-empty">
          暂无执行记录（定时任务执行后会在此展示）
        </div>
        <div v-else class="overview-records-list">
          <div v-for="record in recentRecords" :key="`${record.jobId}-${record.recordId}`" class="overview-record-item">
            <ATag :color="record.jobType === 'WORKFLOW' ? 'purple' : 'blue'">
              {{ record.jobType === 'WORKFLOW' ? '工作流' : '智能体' }}
            </ATag>
            <span class="overview-record-time">{{ record.createTime }}</span>
            <span class="overview-record-target">记录ID：{{ record.recordId }}</span>
          </div>
        </div>
      </div>

      <ExecutionNodes />
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

.overview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.overview-title {
  color: #1f2737;
  font-size: 15px;
  font-weight: 600;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.overview-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  background: #fafcff;
}

.overview-icon {
  display: flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  font-size: 19px;

  &.icon-session {
    color: #4449d0;
    background: #eef0ff;
  }

  &.icon-job {
    color: #d48806;
    background: #fff7e6;
  }

  &.icon-node {
    color: #1f9254;
    background: #eaf7ef;
  }

  &.icon-run {
    color: #1677ff;
    background: #eef4ff;
  }
}

.overview-label {
  color: #8c98a8;
  font-size: 12px;
}

.overview-value {
  margin-top: 4px;
  color: #1f2737;
  font-size: 20px;
  font-weight: 600;

  small {
    margin-left: 4px;
    color: #98a4b8;
    font-size: 12px;
    font-weight: 400;
  }
}

.overview-records {
  margin: 18px 0 22px;
}

.overview-records-head {
  margin-bottom: 8px;
  color: #1f2737;
  font-size: 13px;
  font-weight: 600;
}

.overview-records-empty {
  padding: 14px;
  color: #98a4b8;
  border: 1px dashed #e6ebf3;
  border-radius: 8px;
  font-size: 12px;
  text-align: center;
}

.overview-records-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.overview-record-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  font-size: 12px;
}

.overview-record-time {
  flex: 1;
  color: #2b3445;
}

.overview-record-target {
  color: #8c98a8;
}

@media (max-width: 992px) {
  .overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
