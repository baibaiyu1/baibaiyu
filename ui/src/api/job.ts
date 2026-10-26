import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types'
import type { JobInfo } from '@/types'
import type { JobRecordVO } from '@/types/automation'

/**
 * 定时任务分页查询条件（智能体编排页「定时」分区用）
 */
export interface JobPageQuery {
  /** 页码 */
  page: number
  /** 每页数量 */
  size: number
  /** 任务类型（AGENT / WORKFLOW） */
  type?: string
  /** 关联业务ID（智能体ID） */
  bizId?: string
  /** 名称 / 内容关键字 */
  keyword?: string
  /** 启用状态（不传 = 不限） */
  enabled?: boolean
}

/**
 * 分页查询定时任务
 * GET /job/page
 *
 * @param query 分页 + 过滤条件
 */
export function page(query: JobPageQuery) {
  return request.get<ApiResponse<PageResult<JobInfo>>>('/api/runtime/job/page', {
    params: {
      page: query.page,
      size: query.size,
      type: query.type || undefined,
      bizId: query.bizId || undefined,
      keyword: query.keyword || undefined,
      enabled: query.enabled
    }
  })
}

/**
 * 启用 / 禁用切换
 * GET /job/toggle
 */
export function toggle(id: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/toggle', {
    params: { id }
  })
}

/**
 * 手动触发一次执行
 * GET /job/trigger
 */
export function trigger(id: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/trigger', {
    params: { id }
  })
}

/**
 * 查询所有定时任务
 * GET /job/list
 */
export function list() {
  return request.get<ApiResponse<JobInfo[]>>('/api/runtime/job/list')
}

/**
 * 新增定时任务
 * POST /job/add
 */
export function add(jobInfo: JobInfo) {
  return request.post<ApiResponse<boolean>>('/api/runtime/job/add', jobInfo)
}

/**
 * 修改定时任务
 * POST /job/update
 */
export function update(jobInfo: JobInfo) {
  return request.post<ApiResponse<boolean>>('/api/runtime/job/update', jobInfo)
}

/**
 * 修改定时任务的 cron 表达式
 * GET /job/updateCron
 */
export function updateCron(id: string, cron: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/updateCron', {
    params: { id, cron }
  })
}

/**
 * 删除定时任务
 * GET /job/delete
 */
export function remove(id: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/delete', {
    params: { id }
  })
}

/**
 * 启动定时任务
 * GET /job/start
 */
export function start(id: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/start', {
    params: { id }
  })
}

/**
 * 停止定时任务
 * GET /job/stop
 */
export function stop(id: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/stop', {
    params: { id }
  })
}

/**
 * 根据业务ID查询定时任务
 * GET /job/getByBizId
 */
export function getByBizId(bizId: string) {
  return request.get<ApiResponse<JobInfo>>('/api/runtime/job/getByBizId', {
    params: { bizId }
  })
}

/**
 * 根据业务ID删除定时任务（解绑）
 * GET /job/deleteByBizId
 */
export function deleteByBizId(bizId: string) {
  return request.get<ApiResponse<boolean>>('/api/runtime/job/deleteByBizId', {
    params: { bizId }
  })
}

/**
 * 分页查询任务执行记录
 * GET /runtime/job/records
 *
 * @param jobId 任务ID
 * @param page  页码
 * @param size  每页条数
 */
export function records(jobId: string | number, page = 1, size = 50) {
  return request.get<ApiResponse<PageResult<JobRecordVO>>>('/api/runtime/job/records', {
    params: { jobId, page, size }
  })
}
