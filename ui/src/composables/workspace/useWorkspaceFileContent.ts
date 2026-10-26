/**
 * 工作空间文件内容加载
 *
 * 为右栏预览器提供统一的「取内容」能力，屏蔽掉 blob 与文本两种读法的差异，
 * 并集中处理三件容易出错的琐事：
 *   1. blob URL 的生命周期（切换文件 / 卸载时必须 revoke，否则内存泄漏）
 *   2. 竞态（快速连点不同文件时，慢的那个响应不能覆盖新的）
 *   3. 扩展名兜底（调用方可能只给路径，不给 extension）
 */
import { computed, onUnmounted, ref, watch, type Ref } from 'vue'
import * as workspaceApi from '@/api/workspace'
import {
  isMediaKind,
  isTextBasedKind,
  normalizeExtension,
  resolveFileKind,
  type FileKind
} from '@/components/workspace/viewer/fileKind'

/** 预览目标文件的最小描述（兼容 WorkspaceFileNode 与 FlatFileItem） */
export interface PreviewFileRef {
  /** 工作空间内的相对路径 */
  path: string
  /** 文件名（不含目录） */
  name?: string
  /** 文件名（含后缀） */
  fullName?: string
  /** 扩展名（不含点号） */
  extension?: string
}

/**
 * 从文件描述中解析扩展名
 *
 * 优先用显式 extension，缺失时从文件名/路径末段推断。
 *
 * @param file 预览目标
 * @returns 小写、不含点号的扩展名
 */
function deriveExtension(file: PreviewFileRef | null): string {
  if (!file) return ''
  if (file.extension) return normalizeExtension(file.extension)
  const name = file.fullName || file.name || file.path || ''
  const base = name.split('/').pop() ?? ''
  const dot = base.lastIndexOf('.')
  return dot > -1 ? base.slice(dot + 1).toLowerCase() : ''
}

/**
 * 加载工作空间文件内容
 *
 * @param sessionId 当前会话 ID
 * @param file 预览目标文件（响应式，变化时自动重新加载）
 * @returns 内容状态与重新加载方法
 */
export function useWorkspaceFileContent(
  sessionId: Ref<string | null>,
  file: Ref<PreviewFileRef | null>
) {
  /** 是否加载中 */
  const loading = ref(false)
  /** 错误信息（null 表示无错误） */
  const error = ref<string | null>(null)
  /** 文本类内容 */
  const textContent = ref('')
  /** 媒体类内容的 blob URL；非媒体恒为空串 */
  const blobUrl = ref('')

  /** 扩展名 */
  const extension = computed(() => deriveExtension(file.value))

  /** 渲染器种类 */
  const kind = computed<FileKind>(() => resolveFileKind(extension.value))

  /** 文件名（用于标题展示） */
  const fileName = computed(() => {
    const f = file.value
    if (!f) return ''
    return f.fullName || f.name || f.path.split('/').pop() || f.path
  })

  /** 单调递增的请求序号，用于丢弃过期响应 */
  let requestSeq = 0

  /**
   * 释放当前持有的 blob URL
   */
  function releaseBlob() {
    if (blobUrl.value.startsWith('blob:')) {
      URL.revokeObjectURL(blobUrl.value)
    }
    blobUrl.value = ''
  }

  /**
   * 清空全部内容状态
   */
  function reset() {
    releaseBlob()
    textContent.value = ''
    error.value = null
    loading.value = false
  }

  /**
   * 按文件类型加载内容
   */
  async function load() {
    const target = file.value
    const sid = sessionId.value

    if (!target || !sid) {
      reset()
      return
    }

    const currentKind = kind.value
    // 不支持预览的类型不做请求，直接交给「下载引导」分支
    if (currentKind === 'unsupported') {
      reset()
      return
    }

    const seq = ++requestSeq
    releaseBlob()
    textContent.value = ''
    error.value = null
    loading.value = true

    try {
      const res = await workspaceApi.download(sid, target.path)
      // 已被更新的请求取代 —— 丢弃本次结果，避免旧内容覆盖新文件
      if (seq !== requestSeq) return

      const blob = res.data as Blob
      if (isMediaKind(currentKind)) {
        blobUrl.value = URL.createObjectURL(blob)
      } else if (isTextBasedKind(currentKind)) {
        textContent.value = await blob.text()
        if (seq !== requestSeq) return
      }
    } catch (e) {
      if (seq !== requestSeq) return
      error.value = e instanceof Error ? e.message : '文件加载失败'
    } finally {
      if (seq === requestSeq) {
        loading.value = false
      }
    }
  }

  // 文件变化即重新加载（immediate 覆盖首次传入的情况）
  watch(
    [() => sessionId.value, () => file.value?.path],
    () => {
      void load()
    },
    { immediate: true }
  )

  onUnmounted(() => {
    // 序号自增使在途请求的结果失效，随后释放 blob
    requestSeq++
    releaseBlob()
  })

  return {
    loading,
    error,
    textContent,
    blobUrl,
    kind,
    extension,
    fileName,
    reload: load
  }
}
