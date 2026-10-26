<script setup lang="ts">
/**
 * 工作空间文件编辑器（「文章编写模式」）
 *
 * 与预览的区别：这里是**可写**的。用于写文章/改代码，右侧可实时预览
 * （markdown 与 html 才给预览面板，其余类型只给编辑器）。
 *
 * 并发保护：打开时记下基线内容，保存时把它随请求发回，后端据此判断
 * 智能体是否在编辑期间改过同一文件。冲突时拒绝覆盖并提示重新打开 ——
 * 静默冲掉智能体刚写的内容是不可接受的。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { SaveOutlined, ArrowLeftOutlined, CheckOutlined } from '@ant-design/icons-vue'
import SmartCodeEditor from '@/components/editor/SmartCodeEditor.vue'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import * as workspaceApi from '@/api/workspace'
import { normalizeExtension } from './fileKind'

const props = defineProps<{
  /** 当前会话 ID */
  sessionId: string | null
  /** 工作空间内相对路径 */
  filePath: string
  /** 文件名（标题展示用） */
  fileName: string
  /** 打开编辑器时的文件内容 */
  initialContent: string
}>()

const emit = defineEmits<{
  /** 返回预览视图 */
  (e: 'back'): void
  /** 保存成功 */
  (e: 'saved', content: string): void
}>()

/** 编辑器中的内容 */
const content = ref(props.initialContent)
/** 基线内容：用于脏检查与并发冲突检测 */
const baseline = ref(props.initialContent)
/** 保存中 */
const saving = ref(false)
/** 刚保存成功的短暂反馈 */
const justSaved = ref(false)

/** 扩展名 */
const extension = computed(() => normalizeExtension(props.filePath.split('.').pop() ?? ''))

/** 是否有未保存改动 */
const dirty = computed(() => content.value !== baseline.value)

/** 是否为 markdown（给实时预览） */
const isMarkdown = computed(() => extension.value === 'md' || extension.value === 'markdown')

/** 是否为 html（给实时预览） */
const isHtml = computed(() => extension.value === 'html' || extension.value === 'htm')

/** 是否显示预览面板 */
const showPreview = computed(() => isMarkdown.value || isHtml.value)

/**
 * 映射到 SmartCodeEditor 支持的语言枚举
 *
 * @param ext 扩展名
 */
function editorLanguage(ext: string): 'java' | 'txt' | 'html' | 'javascript' | 'css' | 'xml' | 'json' | 'markdown' {
  switch (ext) {
    case 'md':
    case 'markdown':
      return 'markdown'
    case 'html':
    case 'htm':
      return 'html'
    case 'css':
    case 'scss':
    case 'less':
      return 'css'
    case 'json':
      return 'json'
    case 'xml':
    case 'svg':
      return 'xml'
    case 'java':
      return 'java'
    case 'js':
    case 'mjs':
    case 'cjs':
    case 'ts':
    case 'tsx':
    case 'jsx':
    case 'vue':
      return 'javascript'
    default:
      return 'txt'
  }
}

/** 编辑器语言 */
const language = computed(() => editorLanguage(extension.value))

/**
 * 保存
 */
async function handleSave() {
  if (!props.sessionId || saving.value || !dirty.value) return
  saving.value = true
  try {
    await workspaceApi.writeFile({
      sessionId: props.sessionId,
      filePath: props.filePath,
      content: content.value,
      // 传基线做冲突检测；新建文件时基线为空串，同样有效
      expectedContent: baseline.value
    })
    baseline.value = content.value
    justSaved.value = true
    setTimeout(() => { justSaved.value = false }, 2000)
    emit('saved', content.value)
    message.success('已保存')
  } catch (e) {
    // 冲突与容量超限都会落到这里，后端已经把原因写在 msg 里
    const text = e instanceof Error ? e.message : '保存失败'
    message.error(text)
  } finally {
    saving.value = false
  }
}

/**
 * 全局快捷键：Ctrl/Cmd + S 保存
 */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    void handleSave()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

/**
 * 返回预览：有未保存改动时先确认
 */
function handleBack() {
  if (dirty.value && !window.confirm('有未保存的改动，确定要离开吗？')) return
  emit('back')
}
</script>

<template>
  <div class="ws-editor">
    <div class="ws-editor-bar">
      <button class="ws-editor-back" title="返回预览" @click="handleBack">
        <ArrowLeftOutlined />
      </button>
      <span class="ws-editor-name" :title="filePath">{{ fileName }}</span>
      <span v-if="dirty" class="ws-editor-dirty" title="有未保存改动">●</span>
      <button
        class="ws-editor-save"
        :class="{ 'is-done': justSaved }"
        :disabled="saving || !dirty"
        :title="dirty ? '保存 (Ctrl+S)' : '没有改动'"
        @click="handleSave"
      >
        <CheckOutlined v-if="justSaved" />
        <SaveOutlined v-else />
        <span>{{ saving ? '保存中' : (justSaved ? '已保存' : '保存') }}</span>
      </button>
    </div>

    <div class="ws-editor-body" :class="{ 'is-split': showPreview }">
      <div class="ws-editor-pane">
        <SmartCodeEditor
          v-model="content"
          :language="language"
          theme="light"
          :show-toolbar="false"
          :show-status-bar="true"
          :show-error-panel="false"
          :show-format-button="false"
          :show-fullscreen="false"
          :show-copy-button="false"
          :show-change-language="false"
          :autofocus="false"
          height="100%"
        />
      </div>

      <!-- markdown / html 给实时预览 -->
      <div v-if="showPreview" class="ws-editor-pane ws-editor-preview">
        <div v-if="isMarkdown" class="ws-editor-preview-inner chat-md-content">
          <MarkdownRenderer :content="content" readonly-interaction />
        </div>
        <iframe
          v-else
          class="ws-editor-html-frame"
          sandbox="allow-scripts allow-forms allow-modals"
          referrerpolicy="no-referrer"
          :srcdoc="content"
          title="HTML 实时预览"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.ws-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.ws-editor-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  height: 40px;
  padding: 0 8px;
  border-bottom: 1px solid #ededed;
}

.ws-editor-back {
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

  &:hover {
    background: rgba(15, 116, 255, 0.08);
    color: #0f74ff;
  }
}

.ws-editor-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  color: #262626;
}

.ws-editor-dirty {
  flex-shrink: 0;
  color: #fa8c16;
  font-size: 12px;
  line-height: 1;
}

.ws-editor-save {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 4px 12px;
  border: 1px solid #0f74ff;
  border-radius: 6px;
  background: #0f74ff;
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: opacity 0.2s, background 0.2s;

  &:disabled {
    border-color: #e5e5e5;
    background: #f5f5f5;
    color: #bfbfbf;
    cursor: not-allowed;
  }

  &.is-done {
    border-color: #52c41a;
    background: #52c41a;
  }
}

.ws-editor-body {
  flex: 1;
  min-height: 0;
  display: flex;

  &.is-split .ws-editor-pane {
    width: 50%;
  }
}

.ws-editor-pane {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ws-editor-preview {
  border-left: 1px solid #ededed;
  overflow: auto;
  background: #fff;
}

.ws-editor-preview-inner {
  padding: 16px 20px 40px;
}

.ws-editor-html-frame {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: none;
  background: #fff;
}
</style>
