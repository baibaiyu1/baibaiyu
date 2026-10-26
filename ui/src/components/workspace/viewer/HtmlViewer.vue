<script setup lang="ts">
/**
 * HTML 文件预览渲染器
 *
 * 沙箱红线：iframe 只允许 allow-scripts/allow-forms/allow-modals，
 * **绝不能加 allow-same-origin**。预览内容是工作空间里由智能体生成的 HTML，属不可信输入；
 * 两者同时开启会让预览页与主应用同源，直接威胁 localStorage 中的登录令牌。
 * （同类问题已在 chat 的 code-handler.ts 修过一次，此处保持一致。）
 *
 * 用**真实 URL 导航**而不是 srcdoc：
 *   1. 相对路径资源（./style.css、./img/x.png）能自然解析 —— srcdoc 做不到；
 *   2. 服务端可下发 nosniff / no-referrer / CSP sandbox 等安全响应头。
 * 代价是 iframe 带不了 Authorization 头（本仓鉴权是 Bearer），
 * 因此走短时效、绑定会话的预览令牌（见后端 WorkspacePreviewController）。
 *
 * 令牌获取失败时回落 srcdoc：自包含的 HTML 仍可预览，只是相对资源加载不了。
 */
import { computed, ref, watch } from 'vue'
import { EyeOutlined, CodeOutlined, WarningOutlined } from '@ant-design/icons-vue'
import * as workspaceApi from '@/api/workspace'
import CodeViewer from './CodeViewer.vue'

const props = defineProps<{
  /** HTML 源文本（用于「源码」视图；也作为令牌失败时的 srcdoc 回落） */
  content: string
  /** 当前会话 ID */
  sessionId: string | null
  /** 工作空间内相对路径 */
  filePath: string
}>()

/** 当前视图：预览 / 源码 */
const view = ref<'preview' | 'source'>('preview')

/** 预览地址（令牌换取成功时才有值） */
const previewUrl = ref('')
/** 令牌获取是否失败 —— 失败则回落 srcdoc，并提示相对资源不可用 */
const tokenFailed = ref(false)

/**
 * 拉取预览令牌并拼出预览地址
 */
async function loadPreviewUrl() {
  previewUrl.value = ''
  tokenFailed.value = false
  if (!props.sessionId || !props.filePath) {
    tokenFailed.value = true
    return
  }
  try {
    const res = await workspaceApi.previewToken(props.sessionId)
    const token = res.data.data
    if (!token) {
      tokenFailed.value = true
      return
    }
    previewUrl.value = workspaceApi.rawPreviewUrl(token, props.sessionId, props.filePath)
  } catch (e) {
    console.error('预览令牌获取失败:', e)
    tokenFailed.value = true
  }
}

watch(
  () => [props.sessionId, props.filePath] as const,
  () => { void loadPreviewUrl() },
  { immediate: true }
)

/**
 * 是否引用了相对路径资源（仅在 srcdoc 回落时提示，因为真实 URL 下它们是能加载的）
 */
const hasRelativeAssets = computed(() => {
  const html = props.content ?? ''
  const attrRe = /(?:src|href)\s*=\s*["']([^"']+)["']/gi
  let match: RegExpExecArray | null
  while ((match = attrRe.exec(html)) !== null) {
    const url = match[1] ?? ''
    if (/^(?:\.{1,2}\/|\/(?!\/))/i.test(url)) return true
  }
  return false
})
</script>

<template>
  <div class="html-viewer">
    <div class="html-viewer-bar">
      <button
        class="html-viewer-tab"
        :class="{ 'is-active': view === 'preview' }"
        @click="view = 'preview'"
      >
        <EyeOutlined /> 预览
      </button>
      <button
        class="html-viewer-tab"
        :class="{ 'is-active': view === 'source' }"
        @click="view = 'source'"
      >
        <CodeOutlined /> 源码
      </button>
    </div>

    <!-- 仅在回落 srcdoc 且确实引用了相对资源时才提示 -->
    <div v-if="view === 'preview' && tokenFailed && hasRelativeAssets" class="html-viewer-hint">
      <WarningOutlined />
      未能获取预览令牌，相对路径资源无法加载（自包含的 HTML 不受影响）
    </div>

    <div class="html-viewer-body">
      <!-- 沙箱：绝不含 allow-same-origin，见文件头注释 -->
      <iframe
        v-if="view === 'preview' && previewUrl"
        class="html-viewer-frame"
        :src="previewUrl"
        sandbox="allow-scripts allow-forms allow-modals"
        referrerpolicy="no-referrer"
        title="HTML 预览"
      />
      <iframe
        v-else-if="view === 'preview'"
        class="html-viewer-frame"
        sandbox="allow-scripts allow-forms allow-modals"
        referrerpolicy="no-referrer"
        :srcdoc="content"
        title="HTML 预览"
      />
      <CodeViewer v-else :content="content" extension="html" wrappable />
    </div>
  </div>
</template>

<style scoped lang="scss">
.html-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.html-viewer-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  height: 34px;
  padding: 0 10px;
  border-bottom: 1px solid #ededed;
}

.html-viewer-tab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: none;
  border-radius: 4px;
  background: transparent;
  font-size: 12px;
  color: #8c8c8c;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;

  &:hover {
    color: #0f74ff;
  }

  &.is-active {
    color: #0f74ff;
    background: rgba(15, 116, 255, 0.1);
    font-weight: 500;
  }
}

.html-viewer-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 6px 10px;
  background: #fffbe6;
  border-bottom: 1px solid #ffe58f;
  font-size: 12px;
  color: #d48806;
}

.html-viewer-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.html-viewer-frame {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: none;
  background: #fff;
}
</style>
