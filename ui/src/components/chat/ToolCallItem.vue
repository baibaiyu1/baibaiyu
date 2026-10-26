<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  id: string,
  name: string
  args?: string
  result?: string
  elapsed?: number
  loading?: boolean
  needConfirm?: boolean
}>()

const isKnowledgeRetrieval = computed(() => props.name === 'retrieve_knowledge')

const emit = defineEmits<{
  (e: 'toolContent', value: any): void
}>()

const foldArgs = ref<boolean>(true)

/** 允许：仅记录决策，工具实际由后端 resume 续跑执行（天然带租户/MCP 上下文） */
const handleConfirm = (id: string, name: string) => {
  emit('toolContent', { toolUseId: id, name, approved: true })
}

/** 禁止：仅记录决策，后端 resume 时喂入「拒绝授权」错误结果，不再前端塞文本 */
const handleCancel = (id: string, name: string) => {
  emit('toolContent', { toolUseId: id, name, approved: false })
}

/*查看参数*/
const handleShowArgs = () => {
  foldArgs.value = !foldArgs.value
}
</script>

<template>
  <div>
    <div class="chat-tool-call" :class="{ 'chat-tool-call--loading': loading }" role="status" :aria-busy="loading && !needConfirm">
      <span class="chat-tool-call-dot"></span>
      <span class="chat-tool-call-label">
      <span v-if="loading && !needConfirm" class="chat-tool-call-status">
        {{ isKnowledgeRetrieval ? '正在查询知识库' : `正在执行 ${name}` }}
        <span v-if="isKnowledgeRetrieval" class="knowledge-query-dots" aria-hidden="true"><i v-for="index in 3" :key="index" /></span>
      </span>
      <template v-if="needConfirm" >
        <div class="chat-tool-call-actions">
          <AButton v-if="args && args !== '{}'"
                   type="link"
                   size="small"
                   @click="handleShowArgs">
            {{ `${foldArgs ? '展开参数' : '折叠参数'}` }}
          </AButton>
          <AButton type="primary" size="small" @click="handleConfirm(id, name)">{{ name === 'plan_exit' ? '批准方案并执行' : '允许' }}</AButton>
          <AButton size="small" @click="handleCancel(id, name)">{{ name === 'plan_exit' ? '拒绝方案' : '禁止' }}</AButton>
        </div>
      </template>
    </span>
    </div>
    <div class="chat-tool-call" v-if="args && args !== '{}' && !foldArgs">
      {{ args && args !== '{}' ? args : '无参数' }}
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/chat/index.scss' as *;

.chat-tool-call-status { display: inline-flex; align-items: center; gap: 5px; }
.knowledge-query-dots { display: inline-flex; align-items: center; gap: 3px; }
.knowledge-query-dots i { width: 3px; height: 3px; border-radius: 50%; background: currentColor; animation: knowledge-query-pulse 1.2s ease-in-out infinite; }
.knowledge-query-dots i:nth-child(2) { animation-delay: .2s; }
.knowledge-query-dots i:nth-child(3) { animation-delay: .4s; }
@keyframes knowledge-query-pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-2px); } }
@media (prefers-reduced-motion: reduce) { .knowledge-query-dots i { animation: none; opacity: .65; } }
</style>
