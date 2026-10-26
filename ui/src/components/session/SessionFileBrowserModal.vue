<script setup lang="ts">
/**
 * 会话文件浏览器弹窗
 *
 * 「会话文件管理」页「查看文件」的落点：把某个会话的**工作空间**摆进弹窗里管理
 * —— 左侧文件树（点击即在右侧内联预览）+ 上传 / 下载 / 删除 / 清空 / 容量。
 *
 * 文件树、工具栏、预览器全部复用 `components/workspace/**`：
 * 这里只补「弹窗容器」和一套文件操作逻辑，不重写渲染器，
 * 避免与对话页右栏（WorkspacePanel）出现两套行为不一致的文件管理。
 *
 */
import { computed, reactive, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import { FolderOpenOutlined } from '@ant-design/icons-vue'
import WorkspaceToolbar from '@/components/workspace/WorkspaceToolbar.vue'
import WorkspaceFileTree from '@/components/workspace/WorkspaceFileTree.vue'
import WorkspaceFilePreview from '@/components/workspace/WorkspaceFilePreview.vue'
import WorkspaceFileViewer from '@/components/workspace/viewer/WorkspaceFileViewer.vue'
import { isMediaKind, resolveFileKind } from '@/components/workspace/viewer/fileKind'
import * as workspaceApi from '@/api/workspace'
import type { WorkspaceFileNode, WorkspaceCapacityVO } from '@/types'

const props = defineProps<{
  /** 弹窗可见性（配合 v-model:open 使用） */
  open: boolean
  /** 目标会话 ID；为空时弹窗内不加载任何文件 */
  sessionId: string | null
  /** 弹窗标题里的会话名 */
  sessionTitle?: string
  /** 归属智能体名，用于标题副文案 */
  agentName?: string
  /** Server-authorized write capability for this session. */
  canWrite: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  /**
   * 文件被改动（上传/删除/清空）后触发
   * 页面据此刷新该会话的容量与使用率，避免列表里的用量停留在旧值
   */
  (e: 'changed'): void
}>()

/** 文件树节点列表 */
const nodes = ref<WorkspaceFileNode[]>([])
/** 是否加载中 */
const loading = ref(false)
/** 是否正在上传 */
const uploading = ref(false)
/** 是否处于多选模式 */
const multiSelect = ref(false)
/** 已选中的文件路径集合 */
const selectedPaths = reactive(new Set<string>())
/** 正在被操作的文件路径集合 */
const operatingPaths = reactive(new Set<string>())
/** 工作空间容量信息 */
const capacity = ref<WorkspaceCapacityVO | null>(null)
/** 当前预览的文件；为空表示停留在文件树视图 */
const previewNode = ref<WorkspaceFileNode | null>(null)
/** 媒体类预览弹窗（图片/音视频）可见性 */
const previewVisible = ref(false)

const hasFiles = computed(() => nodes.value.length > 0)
const selectedCount = computed(() => selectedPaths.size)

/** 弹窗标题 */
const modalTitle = computed(() => props.sessionTitle || '会话文件')

/**
 * 弹窗可见性双向绑定
 *
 * 不能只把 `update:open` 透传给 AModal：AModal 的 `update:open` 是受控回写，
 * 关掉后父级必须收到 false，否则再次打开同一会话时 v-if 不会重新挂载、状态会串。
 */
const visible = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value)
})

/**
 * 下载 Blob 到本地
 */
const triggerBlobDownload = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/**
 * 拉取工作空间容量
 */
const fetchCapacity = async () => {
  if (!props.sessionId) return
  try {
    const res = await workspaceApi.getCapacity(props.sessionId)
    capacity.value = res.data.data ?? null
  } catch (e) {
    // 容量接口失败不影响文件树主流程
    console.warn('[会话文件] 查询工作空间容量失败', props.sessionId, e)
  }
}

/**
 * 刷新文件树（并同步容量）
 */
const fetchFiles = async () => {
  if (!props.sessionId) {
    nodes.value = []
    capacity.value = null
    return
  }
  loading.value = true
  try {
    const res = await workspaceApi.listFiles(props.sessionId)
    nodes.value = res.data.data ?? []
    await fetchCapacity()
  } catch (e) {
    console.error('[会话文件] 加载文件树失败', e)
    nodes.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 操作成功后统一刷新，并把用量变化同步给外层列表
 */
const refreshAfterMutation = async () => {
  await fetchFiles()
  emit('changed')
}

/**
 * 是否为压缩包（走上传并解压）
 */
const isArchive = (file: File): boolean => {
  const name = file.name.toLowerCase()
  return /\.(zip|tar|gz|rar|7z|bz2|xz|tgz)$/.test(name)
}

/**
 * 上传（工具栏选择或拖拽）
 */
const handleUpload = async (files: File[]) => {
  if (!props.canWrite || !props.sessionId || !files.length) return
  uploading.value = true
  try {
    const archives = files.filter(isArchive)
    const normals = files.filter((f) => !isArchive(f))

    const tasks: Promise<unknown>[] = []
    for (const archive of archives) {
      tasks.push(workspaceApi.uploadArchive(props.sessionId, archive))
    }
    if (normals.length > 0) {
      tasks.push(workspaceApi.uploadBatch(props.sessionId, normals))
    }
    await Promise.all(tasks)
    await refreshAfterMutation()
  } catch (e) {
    console.error('[会话文件] 上传失败', e)
  } finally {
    uploading.value = false
  }
}

/**
 * 切换选中状态
 */
const handleSelect = (path: string) => {
  if (selectedPaths.has(path)) {
    selectedPaths.delete(path)
  } else {
    selectedPaths.add(path)
  }
}

/**
 * 收集树中所有文件路径（递归）
 */
const collectFilePaths = (nodeList: WorkspaceFileNode[]): string[] => {
  const paths: string[] = []
  for (const n of nodeList) {
    if (n.directory) {
      paths.push(...collectFilePaths(n.children ?? []))
    } else {
      paths.push(n.path)
    }
  }
  return paths
}

/**
 * 按路径在树中查找节点（递归）
 */
const findNodeByPath = (nodeList: WorkspaceFileNode[], path: string): WorkspaceFileNode | null => {
  for (const n of nodeList) {
    if (n.path === path) return n
    if (n.directory && n.children) {
      const found = findNodeByPath(n.children, path)
      if (found) return found
    }
  }
  return null
}

/**
 * 打开预览
 *
 * 分流与对话页右栏一致：图片/音视频仍走全屏沉浸式弹窗（缩放旋转拖拽在窄容器里体验差），
 * 其余类型在弹窗右侧内联渲染（代码高亮 / Markdown / JSON 树 / 表格 / HTML 沙箱）。
 */
const handlePreview = (node: WorkspaceFileNode) => {
  previewNode.value = node
  if (isMediaKind(resolveFileKind(node.extension))) {
    previewVisible.value = true
  }
}

/** 关闭内联预览，回到文件树 */
const closePreview = () => {
  previewNode.value = null
  previewVisible.value = false
}

/**
 * 删除选中文件（串行执行，兼容无批量删除接口）
 */
const handleDelete = () => {
  if (!props.canWrite || !props.sessionId || selectedPaths.size === 0) return
  const paths = Array.from(selectedPaths)
  Modal.confirm({
    title: '确认删除',
    content: `将删除所选 ${paths.length} 个文件，此操作不可恢复，是否继续？`,
    okText: '确认删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      for (const p of paths) operatingPaths.add(p)
      try {
        for (const filePath of paths) {
          await workspaceApi.deleteFile(props.sessionId as string, filePath)
        }
        selectedPaths.clear()
        multiSelect.value = false
        // 被删的文件若正在预览，必须退回文件树，否则右侧停留在已不存在的文件上
        if (previewNode.value && paths.includes(previewNode.value.path)) {
          closePreview()
        }
        await refreshAfterMutation()
      } catch (e) {
        console.error('[会话文件] 删除失败', e)
      } finally {
        for (const p of paths) operatingPaths.delete(p)
      }
    }
  })
}

/**
 * 删除单个文件（文件树右键菜单）
 */
const handleDeleteFile = (node: WorkspaceFileNode) => {
  if (!props.canWrite || !props.sessionId) return
  Modal.confirm({
    title: '确认删除',
    content: `将删除 [${node.fullName || node.name}]，此操作不可恢复，是否继续？`,
    okText: '确认删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      operatingPaths.add(node.path)
      try {
        await workspaceApi.deleteFile(props.sessionId as string, node.path)
        selectedPaths.delete(node.path)
        if (previewNode.value?.path === node.path) {
          closePreview()
        }
        await refreshAfterMutation()
      } catch (e) {
        console.error('[会话文件] 删除失败', e)
      } finally {
        operatingPaths.delete(node.path)
      }
    }
  })
}

/**
 * 下载选中文件：单个直连下载接口，多个走打包接口
 */
const handleDownload = async () => {
  if (!props.sessionId || selectedPaths.size === 0) return
  const sid = props.sessionId
  const paths = Array.from(selectedPaths)
  try {
    if (paths.length === 1) {
      const only = paths[0] as string
      const node = findNodeByPath(nodes.value, only)
      const filename = node?.path ?? only
      const res = await workspaceApi.download(sid, filename)
      triggerBlobDownload(res.data as Blob, node?.fullName || node?.name || filename)
    } else {
      const res = await workspaceApi.downloadBatch(sid, paths)
      triggerBlobDownload(res.data as Blob, 'workspace_files.zip')
    }
  } catch (e) {
    console.error('[会话文件] 下载失败', e)
  }
}

/**
 * 下载单个文件（文件树右键菜单）
 */
const handleDownloadFile = async (node: WorkspaceFileNode) => {
  if (!props.sessionId) return
  try {
    const res = await workspaceApi.download(props.sessionId, node.path)
    triggerBlobDownload(res.data as Blob, node.fullName || node.name)
  } catch (e) {
    console.error('[会话文件] 下载失败', e)
  }
}

/**
 * 下载整个工作空间（打包 ZIP）
 */
const handleDownloadAll = async () => {
  if (!props.sessionId) return
  try {
    const res = await workspaceApi.downloadAll(props.sessionId)
    triggerBlobDownload(res.data as Blob, 'workspace.zip')
  } catch (e) {
    console.error('[会话文件] 打包下载失败', e)
  }
}

/**
 * 清空工作空间
 */
const handleClear = () => {
  if (!props.canWrite || !props.sessionId) return
  const total = collectFilePaths(nodes.value).length
  Modal.confirm({
    title: '确认清空',
    content: `将删除该会话工作空间内的全部 ${total} 个文件，此操作不可恢复，是否继续？`,
    okText: '确认清空',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      try {
        await workspaceApi.clearWorkspace(props.sessionId as string)
        selectedPaths.clear()
        multiSelect.value = false
        closePreview()
        await refreshAfterMutation()
      } catch (e) {
        console.error('[会话文件] 清空工作空间失败', e)
      }
    }
  })
}

/**
 * 切换会话时重置全部视图状态
 *
 * 弹窗由外层 v-if 控制挂载（关闭即销毁），但「查看文件」连着点不同会话时
 * 组件会复用同一个实例，这里必须显式清空，否则会残留上一个会话的选中项与预览。
 */
watch(
  () => props.sessionId,
  () => {
    nodes.value = []
    capacity.value = null
    selectedPaths.clear()
    multiSelect.value = false
    operatingPaths.clear()
    closePreview()
    if (props.open) {
      void fetchFiles()
    }
  }
)

/** 打开弹窗时加载文件树 */
watch(
  () => props.open,
  (value) => {
    if (value) {
      void fetchFiles()
    }
  },
  { immediate: true }
)
</script>

<template>
  <AModal
    v-model:open="visible"
    :title="modalTitle"
    :footer="null"
    :width="980"
    :destroyOnClose="true"
    wrap-class-name="session-file-modal"
  >
    <div class="session-file-body">
      <!-- 副标题：会话归属与当前占用 -->
      <div class="session-file-meta">
        <span class="session-file-meta-agent">
          <FolderOpenOutlined />
          {{ agentName || '未知智能体' }}
        </span>
        <span v-if="capacity" class="session-file-meta-capacity">
          {{ capacity.usedReadable }} / {{ capacity.maxReadable }}
        </span>
      </div>

      <!-- 工具栏与文件树只在文件树视图出现；预览视图把空间让给内容 -->
      <template v-if="!previewNode">
        <WorkspaceToolbar
          :multi-select="multiSelect"
          :selected-count="selectedCount"
          :has-files="hasFiles"
          :uploading="uploading"
          :capacity="capacity"
          :can-write="canWrite"
          @upload="handleUpload"
          @delete="handleDelete"
          @download="handleDownload"
          @clear="handleClear"
          @refresh="fetchFiles"
        />
      </template>

      <!-- 主体：文件树 或 内联预览 -->
      <div class="session-file-main">
        <WorkspaceFileViewer
          v-if="previewNode"
          :session-id="sessionId"
          :file="previewNode"
          :can-write="canWrite"
          @back="closePreview"
        />
        <WorkspaceFileTree
          v-else
          :nodes="nodes"
          :session-id="sessionId"
          :loading="loading"
          :selected-paths="selectedPaths"
          :multi-select="multiSelect"
          :operating-paths="operatingPaths"
          :can-write="canWrite"
          @select="handleSelect"
          @preview="handlePreview"
          @download-file="handleDownloadFile"
          @delete-file="handleDeleteFile"
          @drop-upload="handleUpload"
        />
      </div>

      <!-- 底部：容量与打包下载 -->
      <div class="session-file-footer">
        <span class="session-file-footer-capacity">
          <template v-if="capacity">存储空间：{{ capacity.usedReadable }} / {{ capacity.maxReadable }}</template>
        </span>
        <AButton size="small" :disabled="!hasFiles" @click="handleDownloadAll">
          打包下载
        </AButton>
      </div>
    </div>

    <!-- 媒体类预览（图片/音视频）仍走全屏沉浸式弹窗 -->
    <WorkspaceFilePreview
      v-model:visible="previewVisible"
      :file-node="previewNode"
      :session-id="sessionId"
    />
  </AModal>
</template>

<style scoped lang="scss">
@use '@/styles/chat/workspace.scss' as *;

.session-file-body {
  display: flex;
  flex-direction: column;
  height: 62vh;
  min-height: 420px;
}

.session-file-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 2px 10px;
  color: #98a4b8;
  font-size: 12px;
}

.session-file-meta-agent {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.session-file-meta-capacity {
  white-space: nowrap;
}

/* 与 WorkspacePanel 的 .workspace-body 同构，让文件树/预览器拿到确定的高度 */
.session-file-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #ededed;
  border-radius: 8px;
  background-color: #f2f4f7;
}

.session-file-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
}

.session-file-footer-capacity {
  color: #98a4b8;
  font-size: 12px;
}
</style>
