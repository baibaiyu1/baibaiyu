<script setup lang="ts">
/**
 * 工作空间文件预览分发器
 *
 * 按扩展名把文件交给对应渲染器。这是右栏预览的入口组件：
 * 文件树点击、对话内产物卡片点击，最终都落到这里。
 *
 * 媒体类（图片/音视频）不走本组件 —— 它们保留全屏沉浸式 Modal
 * （缩放/旋转/拖拽体验优于 500px 侧栏），由调用方分流。
 */
import { computed, ref } from 'vue'
import { ArrowLeftOutlined, DownloadOutlined, ReloadOutlined, FileTextOutlined, EditOutlined } from '@ant-design/icons-vue'
import * as workspaceApi from '@/api/workspace'
import { useWorkspaceFileContent, type PreviewFileRef } from '@/composables/workspace/useWorkspaceFileContent'
import { resolveFileKind } from './fileKind'
import CodeViewer from './CodeViewer.vue'
import MarkdownViewer from './MarkdownViewer.vue'
import JsonViewer from './JsonViewer.vue'
import TableViewer from './TableViewer.vue'
import HtmlViewer from './HtmlViewer.vue'
import TextViewer from './TextViewer.vue'
import WorkspaceEditor from './WorkspaceEditor.vue'
import type { Ref } from 'vue'

const props = defineProps<{
  /** 当前会话 ID */
  sessionId: string | null
  /** 预览目标文件 */
  file: PreviewFileRef | null
  /** Whether text editing is allowed. */
  canWrite?: boolean
}>()

const emit = defineEmits<{
  /** 返回文件树 */
  (e: 'back'): void
}>()

// props 转成 ref 供 composable 观察（ComputedRef 本身即 Ref）
const fileRef = computed(() => props.file)
const sessionRef = computed(() => props.sessionId)

const { loading, error, textContent, kind, extension, fileName, reload } =
  useWorkspaceFileContent(sessionRef, fileRef)

/** 供 TableViewer 判定分隔符 */
const tableExtension = computed(() => extension.value)

/**
 * 下载当前文件
 */
async function handleDownload() {
  if (!props.file || !props.sessionId) return
  try {
    const res = await workspaceApi.download(props.sessionId, props.file.path)
    const url = URL.createObjectURL(res.data as Blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName.value
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (e) {
    console.error('下载失败:', e)
  }
}

/** 当前是否可以渲染 */
const renderable = computed(() => kind.value !== 'unsupported')

/** 是否处于编辑模式（「文章编写模式」） */
const editing = ref(false)

/**
 * 是否可编辑
 *
 * 媒体与不支持的类型不给编辑入口；其余文本类都可以写。
 */
const editable = computed(() =>
  props.canWrite !== false && ['code', 'markdown', 'json', 'table', 'text', 'html'].includes(kind.value)
)

/**
 * 从编辑器返回：重新拉一次内容
 *
 * 保存后重新读取而非直接用编辑器里的值，这样也能顺带拿到智能体并发写入的结果。
 */
function handleEditorBack() {
  editing.value = false
  reload()
}
</script>

<template>
  <!-- 编辑模式：整块交给编辑器（它自带返回/保存工具条） -->
  <WorkspaceEditor
    v-if="editing"
    :session-id="sessionId"
    :file-path="file?.path ?? ''"
    :file-name="fileName"
    :initial-content="textContent"
    @back="handleEditorBack"
  />

  <div v-else class="ws-file-viewer">
    <!-- 顶部：返回 + 文件名 + 操作 -->
    <div class="ws-file-viewer-header">
      <button class="ws-file-viewer-back" title="返回文件列表" @click="emit('back')">
        <ArrowLeftOutlined />
      </button>
      <span class="ws-file-viewer-name" :title="fileName">{{ fileName }}</span>
      <button
        v-if="editable && !loading && !error"
        class="ws-file-viewer-btn"
        title="编辑（文章编写模式）"
        @click="editing = true"
      >
        <EditOutlined />
      </button>
      <button class="ws-file-viewer-btn" title="重新加载" @click="reload">
        <ReloadOutlined />
      </button>
      <button class="ws-file-viewer-btn" title="下载" @click="handleDownload">
        <DownloadOutlined />
      </button>
    </div>

    <!-- 内容区 -->
    <div class="ws-file-viewer-body">
      <div v-if="loading" class="ws-file-viewer-status">加载中…</div>

      <div v-else-if="error" class="ws-file-viewer-status is-error">
        {{ error }}
      </div>

      <div v-else-if="!renderable" class="ws-file-viewer-unsupported">
        <FileTextOutlined class="ws-file-viewer-unsupported-icon" />
        <p>该文件类型暂不支持预览</p>
        <button class="ws-file-viewer-download" @click="handleDownload">
          <DownloadOutlined /> 下载文件
        </button>
      </div>

      <CodeViewer
        v-else-if="kind === 'code'"
        :content="textContent"
        :extension="extension"
        wrappable
      />
      <MarkdownViewer v-else-if="kind === 'markdown'" :content="textContent" />
      <JsonViewer v-else-if="kind === 'json'" :content="textContent" />
      <TableViewer v-else-if="kind === 'table'" :content="textContent" :extension="tableExtension" />
      <HtmlViewer
        v-else-if="kind === 'html'"
        :content="textContent"
        :session-id="sessionId"
        :file-path="file?.path ?? ''"
      />
      <TextViewer v-else :content="textContent" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.ws-file-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.ws-file-viewer-header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  height: 40px;
  padding: 0 8px;
  border-bottom: 1px solid #ededed;
}

.ws-file-viewer-back,
.ws-file-viewer-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #595959;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;

  &:hover {
    background: rgba(15, 116, 255, 0.08);
    color: #0f74ff;
  }
}

.ws-file-viewer-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  color: #262626;
}

.ws-file-viewer-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  // 各渲染器根节点需要撑满
  > * {
    flex: 1;
    min-height: 0;
  }
}

.ws-file-viewer-status {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 13px;
  color: #8c8c8c;

  &.is-error {
    color: #cf1322;
  }
}

.ws-file-viewer-unsupported {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 100%;
  color: #8c8c8c;
  font-size: 13px;
}

.ws-file-viewer-unsupported-icon {
  font-size: 36px;
  color: #d9d9d9;
}

.ws-file-viewer-download {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  background: #fff;
  color: #595959;
  font-size: 13px;
  cursor: pointer;

  &:hover {
    color: #0f74ff;
    border-color: #0f74ff;
  }
}
</style>
