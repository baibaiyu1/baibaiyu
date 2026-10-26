<template>
  <!-- 流式未完成 → 骨架屏 -->
  <MermaidSkeleton v-if="isStreaming" />
  <!-- 流式完成 → 图表 -->
  <MermaidGraph v-else :code="code" />
</template>

<script setup lang="ts">
import MermaidSkeleton from '@/components/markdown/mermaid/MermaidSkeleton.vue'
import MermaidGraph from '@/components/markdown/mermaid/MermaidGraph.vue'
// ---------- Props ----------
defineProps<{
  /** Mermaid 代码块原始内容 */
  code: string
  /** 是否处于流式输出阶段 */
  isStreaming?: boolean
}>()

</script>

<style scoped>
</style>
