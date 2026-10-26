<script setup lang="ts">
/**
 * 纯文本预览渲染器
 *
 * 用于日志、配置等无专属渲染器的文本。默认开启自动换行——
 * 文本预览的主要场景是读日志，横向滚动体验差。
 */
import { ref } from 'vue'

defineProps<{
  /** 文件全文 */
  content: string
}>()

/** 是否自动换行，文本场景默认开启 */
const wrap = ref(true)
</script>

<template>
  <div class="text-viewer">
    <pre class="text-viewer-body" :class="{ 'is-wrap': wrap }">{{ content }}</pre>
    <button class="text-viewer-toggle" :title="wrap ? '关闭自动换行' : '开启自动换行'" @click="wrap = !wrap">
      {{ wrap ? '不换行' : '自动换行' }}
    </button>
  </div>
</template>

<style scoped lang="scss">
.text-viewer {
  position: relative;
  height: 100%;
  min-height: 0;
  overflow: auto;
  background: #fff;
}

.text-viewer-body {
  margin: 0;
  padding: 12px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.7;
  color: #262626;
  white-space: pre;

  &.is-wrap {
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.text-viewer-toggle {
  position: absolute;
  top: 8px;
  right: 12px;
  padding: 2px 8px;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.92);
  font-size: 12px;
  color: #8c8c8c;
  cursor: pointer;

  &:hover {
    color: #0f74ff;
    border-color: #0f74ff;
  }
}
</style>
