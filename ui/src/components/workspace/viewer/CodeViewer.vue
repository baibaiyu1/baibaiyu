<script setup lang="ts">
/**
 * 代码预览渲染器
 *
 * 用 highlight.js 做只读高亮。选它而不是 CodeMirror 的原因：
 * 预览场景不需要编辑态能力（补全/lint/搜索），hljs 更轻且主题已在 main.ts 全局引入
 * （highlight.js/styles/github.min.css），与聊天代码块观感一致。
 *
 * 编辑态请用 components/editor/SmartCodeEditor.vue（三期「文章编写模式」走那条路）。
 */
import { computed, ref } from 'vue'
import hljs from 'highlight.js'
import { CopyOutlined, CheckOutlined, ExpandOutlined } from '@ant-design/icons-vue'
import { resolveCodeLanguage } from './fileKind'
import { splitHighlightedLines } from './highlightLines'

const props = defineProps<{
  /** 文件全文 */
  content: string
  /** 文件扩展名，用于选择语言与标题展示 */
  extension?: string
  /** 是否允许切换到自动换行 */
  wrappable?: boolean
}>()

/** 是否自动换行（默认关，与代码阅读习惯一致） */
const wrap = ref(false)
/** 复制成功态 */
const copied = ref(false)

/**
 * 高亮后的逐行 HTML
 *
 * v-html 在此是安全的：highlight.js 的 highlight() 会转义源码中的 HTML 实体，
 * 输出只含它自己生成的 <span class="hljs-*"> 包装。
 */
const highlightedLines = computed(() => {
  const source = props.content ?? ''
  if (!source) return []

  const lang = resolveCodeLanguage(props.extension)
  let html: string
  try {
    html = lang && hljs.getLanguage(lang)
      ? hljs.highlight(source, { language: lang }).value
      : hljs.highlightAuto(source).value
  } catch {
    // 极端情况下高亮失败，退化为转义后的纯文本，不能让预览整体崩掉
    html = source.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }

  return splitHighlightedLines(html)
})

/** 行号宽度：按总行数的位数留位，避免长文件行号错位 */
const gutterWidth = computed(() => `${String(highlightedLines.value.length).length + 1}ch`)

/** 展示用语言名 */
const displayLang = computed(() => resolveCodeLanguage(props.extension) || 'text')

/**
 * 复制全文
 */
async function handleCopy() {
  try {
    await navigator.clipboard.writeText(props.content ?? '')
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch (e) {
    console.error('复制失败:', e)
  }
}
</script>

<template>
  <div class="code-viewer">
    <div class="code-viewer-bar">
      <span class="code-viewer-lang">{{ displayLang }}</span>
      <span class="code-viewer-meta">{{ highlightedLines.length }} 行</span>
      <span class="code-viewer-spacer" />
      <button
        v-if="wrappable"
        class="code-viewer-btn"
        :class="{ 'is-active': wrap }"
        :title="wrap ? '关闭自动换行' : '开启自动换行'"
        @click="wrap = !wrap"
      >
        <ExpandOutlined />
      </button>
      <button class="code-viewer-btn" :title="copied ? '已复制' : '复制全文'" @click="handleCopy">
        <CheckOutlined v-if="copied" />
        <CopyOutlined v-else />
      </button>
    </div>

    <div class="code-viewer-body" :class="{ 'is-wrap': wrap }">
      <div
        v-for="(line, index) in highlightedLines"
        :key="index"
        class="code-viewer-row"
      >
        <span class="code-viewer-gutter" :style="{ width: gutterWidth }">{{ index + 1 }}</span>
        <!-- eslint-disable-next-line vue/no-v-html -- 内容来自 highlight.js，已转义源码实体 -->
        <code class="code-viewer-line hljs" v-html="line || '&nbsp;'" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.code-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.code-viewer-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  height: 34px;
  padding: 0 10px;
  border-bottom: 1px solid #ededed;
  font-size: 12px;
  color: #8c8c8c;
}

.code-viewer-lang {
  font-weight: 600;
  color: #595959;
}

.code-viewer-spacer {
  flex: 1;
}

.code-viewer-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8c8c8c;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;

  &:hover {
    background: rgba(15, 116, 255, 0.08);
    color: #0f74ff;
  }

  &.is-active {
    color: #0f74ff;
    background: rgba(15, 116, 255, 0.1);
  }
}

.code-viewer-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.7;
}

.code-viewer-row {
  display: flex;
  align-items: flex-start;

  &:hover {
    background: rgba(15, 116, 255, 0.04);
  }
}

.code-viewer-gutter {
  flex-shrink: 0;
  padding-right: 10px;
  text-align: right;
  color: #bfbfbf;
  user-select: none;
  font-variant-numeric: tabular-nums;
}

.code-viewer-line {
  flex: 1;
  min-width: 0;
  padding-right: 12px;
  white-space: pre;
  // 覆盖 main.ts 引入的 github 主题背景，避免代码块自带底色与面板冲突
  background: transparent !important;
}

// 自动换行模式下改为折行；行号因逐行渲染天然保持对齐
.code-viewer-body.is-wrap .code-viewer-line {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
