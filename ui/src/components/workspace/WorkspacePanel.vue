<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { CloseOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import WorkspaceToolbar from './WorkspaceToolbar.vue'
import WorkspaceFileTree from './WorkspaceFileTree.vue'
import WorkspaceFilePreview from './WorkspaceFilePreview.vue'
import WorkspaceFileViewer from './viewer/WorkspaceFileViewer.vue'
import { isMediaKind, resolveFileKind } from './viewer/fileKind'
import * as workspaceApi from '@/api/workspace'
import type { WorkspaceFileNode, WorkspaceCapacityVO } from '@/types'
import type { FlatFileItem } from '@/composables/chat/useWorkspaceFiles'
import {
  DEFAULT_WORKSPACE_PANEL_WIDTH,
  MIN_WORKSPACE_PANEL_WIDTH,
  MAX_WORKSPACE_PANEL_WIDTH
} from '@/stores/modules/chat'
import { Modal } from "ant-design-vue";
import { WS_MESSAGE_TYPES } from '@/websocket/const/websocket';
import { useWebSocketEvent } from '@/websocket/composables/useWebSocketEvent';

/**
 * 工作空间面板主组件
 * 负责文件树数据管理及所有文件操作逻辑
 */
const props = defineProps<{
  /** 当前会话 ID */
  sessionId: string | null
  /** 右栏宽度（父级按持久化偏好注入；不传则用默认值） */
  width?: number
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'resize', width: number): void
}>()

/** 文件树节点列表 */
const nodes = ref<WorkspaceFileNode[]>([])
/** 是否加载中 */
const loading = ref(false)
/** 是否正在上传 */
const uploading = ref(false)
/** 是否处于多选模式 */
const multiSelect = ref(false)
/** 已选中的文件路径集合（响应式，使用 reactive Set） */
const selectedPaths = reactive(new Set<string>())
/** 正在被操作的文件路径集合 */
const operatingPaths = reactive(new Set<string>())
/** 预览弹窗可见性（仅媒体类使用） */
const previewVisible = ref(false)
/** 当前预览/查看的文件 */
const previewNode = ref<WorkspaceFileNode | FlatFileItem | null>(null)
/** 面板视图：文件树 / 右栏内预览 */
const viewMode = ref<'tree' | 'preview'>('tree')
/** 当前右栏宽度。拖拽期间只改本地值以保证跟手，松手才向上抛一次持久化 */
const panelWidth = ref<number>(props.width ?? DEFAULT_WORKSPACE_PANEL_WIDTH)
/** 是否正在拖拽调整宽度 */
const resizing = ref(false)

// 父级宽度变化时同步（例如切换智能体后读到另一份偏好）
watch(
  () => props.width,
  (value) => {
    if (typeof value === 'number' && value !== panelWidth.value) {
      panelWidth.value = value
    }
  }
)

/**
 * 开始拖拽调整右栏宽度
 *
 * 面板贴靠右侧，因此向左拖拽是变宽（startX - clientX）。
 * 拖拽期间挂 window 监听而不是用 pointer capture，与仓内 MediaPreview 的拖拽写法保持一致。
 */
const startResize = (e: MouseEvent) => {
  e.preventDefault()
  resizing.value = true
  const startX = e.clientX
  const startWidth = panelWidth.value

  const onMove = (ev: MouseEvent) => {
    const next = startWidth + (startX - ev.clientX)
    panelWidth.value = Math.min(
      MAX_WORKSPACE_PANEL_WIDTH,
      Math.max(MIN_WORKSPACE_PANEL_WIDTH, next)
    )
  }

  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    resizing.value = false
    document.body.style.userSelect = ''
    // 只在松手时抛一次，避免拖拽过程中高频写 localStorage
    emit('resize', panelWidth.value)
  }

  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}
/** 工作空间容量信息 */
const capacity = ref<WorkspaceCapacityVO | null>(null)

/** 是否存在文件 */
const hasFiles = computed(() => nodes.value.length > 0)

/** 已选中文件数量 */
const selectedCount = computed(() => selectedPaths.size)

/**
 * 收集树中所有文件的路径（递归）
 * @param nodeList 节点列表
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
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
 * 根据路径在树中查找节点（递归）
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
 * 刷新文件树
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
    // 同步刷新容量信息
    fetchCapacity()
  } finally {
    loading.value = false
  }
}

/**
 * 获取工作空间容量信息
 */
const fetchCapacity = async () => {
  if (!props.sessionId) return
  try {
    const res = await workspaceApi.getCapacity(props.sessionId)
    capacity.value = res.data.data ?? null
  } catch {
    // 容量接口失败不影响主流程
  }
}

/**
 * 判断文件是否为压缩包
 */
const isArchive = (file: File): boolean => {
  const name = file.name.toLowerCase()
  return /\.(zip|tar|gz|rar|7z|bz2|xz|tgz)$/.test(name)
}

/**
 * 触发浏览器下载 Blob 数据
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
 * 处理上传（工具栏或拖拽触发）
 * 区分压缩包与普通文件分别调用不同接口
 */
const handleUpload = async (files: File[]) => {
  if (!props.sessionId || !files.length) return
  uploading.value = true
  try {
    const archives = files.filter(isArchive)
    const normals = files.filter(f => !isArchive(f))

    const tasks: Promise<unknown>[] = []
    // 压缩包逐个上传并解压
    for (const archive of archives) {
      tasks.push(workspaceApi.uploadArchive(props.sessionId, archive))
    }
    // 普通文件批量上传
    if (normals.length > 0) {
      tasks.push(workspaceApi.uploadBatch(props.sessionId, normals))
    }
    await Promise.all(tasks)
    await fetchFiles()
  } finally {
    uploading.value = false
  }
}

/**
 * 处理切换选中状态
 */
const handleSelect = (path: string) => {
  if (selectedPaths.has(path)) {
    selectedPaths.delete(path)
  } else {
    selectedPaths.add(path)
  }
}

/**
 * 打开文件预览
 *
 * 分流策略：
 *   - 图片 / 音频 / 视频 → 保留全屏沉浸式 Modal（缩放旋转拖拽在 500px 侧栏里体验差）
 *   - 其余类型 → 在右栏内用专属渲染器打开（代码高亮 / Markdown / JSON 树 / 表格 / HTML 沙箱）
 *
 * 该方法是「对话内点击产物 → 右栏预览」的落点，已通过 defineExpose 对外暴露。
 *
 * @param node 目标文件，兼容文件树节点与对话内产物引用
 */
const openPreview = (node: WorkspaceFileNode | FlatFileItem) => {
  previewNode.value = node
  if (isMediaKind(resolveFileKind(node.extension))) {
    previewVisible.value = true
    return
  }
  viewMode.value = 'preview'
}

/**
 * 关闭右栏内预览，回到文件树
 */
const closePreview = () => {
  viewMode.value = 'tree'
  previewVisible.value = false
}

/**
 * 预览文件（文件树点击入口）
 *
 * @param node 文件树节点
 */
const handlePreview = (node: WorkspaceFileNode) => {
  openPreview(node)
}

/**
 * 处理删除选中文件（串行执行，兼容无批量删除接口）
 */
const handleDelete = async () => {
  Modal.confirm({
    title: '确认删除',
    content: `将删除所选文件，此操作不可恢复，是否继续？`,
    okText: '确认删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      if (!props.sessionId || selectedPaths.size === 0) return
      const paths = Array.from(selectedPaths)
      for (const p of paths) {
        operatingPaths.add(p)
      }
      try {
        for (const filePath of paths) {
          await workspaceApi.deleteFile(props.sessionId, filePath)
        }
        selectedPaths.clear()
        // 关闭多选模式
        multiSelect.value = false
        await fetchFiles()
      } finally {
        for (const p of paths) {
          operatingPaths.delete(p)
        }
      }
    },
  })
}

/**
 * 处理删除单个文件（来自右键菜单）
 */
const handleDeleteFile = async (node: WorkspaceFileNode) => {
  Modal.confirm({
    title: '确认删除',
    content: `将删除所选[${node.fullName}]，此操作不可恢复，是否继续？`,
    okText: '确认删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      if (!props.sessionId) return
      operatingPaths.add(node.path)
      try {
        await workspaceApi.deleteFile(props.sessionId, node.path)
        selectedPaths.delete(node.path)
        await fetchFiles()
      } finally {
        operatingPaths.delete(node.path)
      }
    },
  })
}

/**
 * 处理下载选中文件
 * 单个文件调用单文件下载接口，多个文件调用批量打包接口
 */
const handleDownload = async () => {
  if (!props.sessionId || selectedPaths.size === 0) return
  const sid = props.sessionId as string
  const paths = Array.from(selectedPaths)
  if (paths.length === 1) {
    const firstPath = paths[0]!
    const node = findNodeByPath(nodes.value, firstPath)
    if (node) {
      const filename = node.path ?? node.fullName ?? node.name
      const res = await workspaceApi.download(sid, filename)
      triggerBlobDownload(res.data as Blob, filename)
    }
  } else {
    const res = await workspaceApi.downloadBatch(sid, paths)
    triggerBlobDownload(res.data as Blob, 'workspace_files.zip')
  }
}

/**
 * 处理下载单个文件（来自右键菜单）
 */
const handleDownloadFile = async (node: WorkspaceFileNode) => {
  if (!props.sessionId) return
  const filename = node.path ?? node.fullName ?? node.name
  const res = await workspaceApi.download(props.sessionId, filename)
  triggerBlobDownload(res.data as Blob, filename)
}

/**
 * 处理清空工作空间
 */
const handleClear = async () => {
  if (!props.sessionId) return
  await workspaceApi.clearWorkspace(props.sessionId)
  selectedPaths.clear()
  multiSelect.value = false
  await fetchFiles()
}

// 监听 sessionId 变化，切换会话时刷新文件树
watch(
  () => props.sessionId,
  (newId) => {
    selectedPaths.clear()
    multiSelect.value = false
    capacity.value = null
    // 切会话必须退回文件树：预览中的文件属于上一个会话的工作空间，留着会展示错内容
    viewMode.value = 'tree'
    previewVisible.value = false
    previewNode.value = null
    if (newId) {
      fetchFiles()
    } else {
      nodes.value = []
    }
  },
  { immediate: true }
)

/**
 * 标记文件开始被操作（供外部或智能体调用）
 *
 * @param path 文件相对路径
 */
const startFileOperation = (path: string) => {
  operatingPaths.add(path)
}

/**
 * 标记文件操作结束（供外部或智能体调用）
 *
 * @param path 文件相对路径
 */
const stopFileOperation = (path: string) => {
  operatingPaths.delete(path)
}

/**
 * 手动刷新文件树（供外部调用）
 */
const refresh = () => {
  fetchFiles()
}

// 订阅 WORKSPACE_FILE_CHANGE 消息
useWebSocketEvent(WS_MESSAGE_TYPES.WORKSPACE_FILE_CHANGE, (message) => {
  const { fileName, sessionId } = message
  if (sessionId !== props.sessionId) {
    return
  }
  fetchFiles()
});

defineExpose({ startFileOperation, stopFileOperation, refresh, openPreview, closePreview })
</script>

<template>
  <div
    class="workspace-panel"
    :class="{ 'is-resizing': resizing }"
    :style="{ '--ws-width': `${panelWidth}px` }"
  >
    <!-- 左边缘拖拽手柄：调整右栏宽度 -->
    <div
      class="workspace-panel-resizer"
      :class="{ 'is-active': resizing }"
      title="拖拽调整宽度"
      @mousedown="startResize"
    />

    <!-- 标题栏 -->
    <div class="workspace-panel-header">
      <span class="workspace-panel-title">
        {{ viewMode === 'preview' ? '文件预览' : '工作空间' }}
        <LoadingOutlined v-if="loading" />
      </span>
      <AButton
        type="text"
        size="small"
        class="workspace-panel-close-btn"
        title="关闭"
        @click="$emit('close')"
      >
        <CloseOutlined />
      </AButton>
    </div>

    <!-- 工具栏与容量条只属于文件树视图；预览视图把空间让给内容 -->
    <template v-if="viewMode === 'tree'">
      <WorkspaceToolbar
        :multi-select="multiSelect"
        :selected-count="selectedCount"
        :has-files="hasFiles"
        :uploading="uploading"
        :capacity="capacity"
        @upload="handleUpload"
        @delete="handleDelete"
        @download="handleDownload"
        @clear="handleClear"
        @refresh="fetchFiles"
      />
    </template>

    <!-- 主体：文件树 或 右栏内预览 -->
    <div class="workspace-body">
      <WorkspaceFileViewer
        v-if="viewMode === 'preview' && previewNode"
        :session-id="sessionId"
        :file="previewNode"
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
        @select="handleSelect"
        @preview="handlePreview"
        @download-file="handleDownloadFile"
        @delete-file="handleDeleteFile"
        @drop-upload="handleUpload"
      />
    </div>

    <!-- 容量信息（仅文件树视图） -->
    <div v-if="viewMode === 'tree' && capacity" class="workspace-capacity">
      <div class="workspace-capacity-info">
        <span class="workspace-capacity-label">存储空间</span>
        <span class="workspace-capacity-text">{{ capacity.usedReadable }} / {{ capacity.maxReadable }}</span>
      </div>
    </div>

    <!-- 媒体类预览弹窗（图片/音视频仍走全屏沉浸式） -->
    <WorkspaceFilePreview
      v-model:visible="previewVisible"
      :file-node="previewNode"
      :session-id="sessionId"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/workspace.scss' as *;
</style>
