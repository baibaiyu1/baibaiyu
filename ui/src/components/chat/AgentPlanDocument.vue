<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getPlanDocument, type PlanDocument } from '@/api/agui/plan'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
const props = defineProps<{ sessionId: string; isRunning: boolean }>()
const emit = defineEmits<{ (e: 'openFile', path: string): void }>()
const document = ref<PlanDocument | null>(null), error = ref('')
const labels = { NONE: '', PLANNING: '只读调查中', AWAITING_APPROVAL: '等待方案批准', APPROVED: '已批准，可执行', REJECTED: '已拒绝，保持只读', STALE: '方案文件已变更，需重新审批' }
let version = 0, timer: ReturnType<typeof setTimeout> | undefined, disposed = false
async function refresh() {
  const current = ++version, sid = props.sessionId
  clearTimeout(timer)
  try {
    const result = await getPlanDocument(sid)
    if (disposed || current !== version) return
    document.value = result; error.value = ''
  } catch (e) { if (!disposed && current === version) error.value = e instanceof Error ? e.message : '方案加载失败' }
  if (!disposed && current === version) timer = setTimeout(refresh,
    props.isRunning || document.value?.status === 'AWAITING_APPROVAL' ? 2000 : 15000)
}
watch(() => [props.sessionId, props.isRunning], (_next, previous) => {
  if (!previous || previous[0] !== props.sessionId) { document.value = null; error.value = '' }
  void refresh()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; version++; clearTimeout(timer) })
</script>
<template>
  <details v-if="document && document.status !== 'NONE'" class="agent-plan-document" :open="document.status !== 'APPROVED'">
    <summary>执行方案 · {{ labels[document.status] }} · 第 {{ document.revision }} 版</summary>
    <p v-if="document.status === 'AWAITING_APPROVAL'">请检查方案，再在“批准方案并执行 / 拒绝方案”处选择。批准只对当前版本生效。</p>
    <p v-if="document.status === 'STALE'">旧批准已失效。请拒绝旧审批，让智能体重新保存并提交方案。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <AButton v-if="document.content" type="link" size="small" @click="emit('openFile', document.path)">在工作区预览方案</AButton>
    <div class="plan-content"><MarkdownRenderer v-if="document.content" :content="document.content" readonly-interaction /><p v-else>正在调查并编写方案。</p></div>
  </details>
</template>
<style scoped>
.agent-plan-document { margin: 8px 0; font-size: 13px; }
summary { cursor: pointer; }
.plan-content { max-height: 280px; overflow: auto; }
</style>
