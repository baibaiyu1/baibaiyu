<script setup lang="ts">
/**
 * Markdown 文件预览渲染器
 *
 * 复用对话页同一套 MarkdownRenderer，保证文件预览与对话正文的排版、代码块、
 * mermaid、katex 表现完全一致（避免两套 markdown 实现漂移）。
 *
 * 两点必要的适配：
 *   1. MarkdownRenderer 的排版样式挂在全局 .chat-md-content 下，需在外层补上该类名；
 *   2. 传 readonly-interaction —— 文件预览是只读场景，不应允许 uip 表单/确认块提交。
 */
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'

defineProps<{
  /** markdown 源文本 */
  content: string
}>()
</script>

<template>
  <div class="markdown-viewer">
    <div class="markdown-viewer-body chat-md-content">
      <MarkdownRenderer :content="content" readonly-interaction />
    </div>
  </div>
</template>

<style scoped lang="scss">
.markdown-viewer {
  height: 100%;
  min-height: 0;
  overflow: auto;
  background: #fff;
}

.markdown-viewer-body {
  max-width: 860px;
  margin: 0 auto;
  padding: 20px 24px 48px;
}
</style>
