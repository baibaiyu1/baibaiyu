<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import * as knowledgeApi from '@/api/knowledge'

const props = defineProps<{ knowledgeBaseConfigId: string; view: 'documents' | 'graph'; name?: string }>()
const loading = ref(true)
const ready = ref(false)
const error = ref('')
const revision = ref(0)
let generation = 0
let loadTimer: ReturnType<typeof setTimeout> | undefined
const title = computed(() => props.view === 'graph' ? '知识图谱' : '文档管理')
const frameUrl = computed(() => `${import.meta.env.BASE_URL}lightrag-management/index.html?knowledgeBaseId=${encodeURIComponent(props.knowledgeBaseConfigId)}&view=${props.view}&v=${revision.value}`)

async function load() {
  const current = ++generation
  clearTimeout(loadTimer)
  loading.value = true
  ready.value = false
  error.value = ''
  try {
    await knowledgeApi.prepareLightRagManagement(props.knowledgeBaseConfigId)
    if (current !== generation) return
    revision.value++
    ready.value = true
    loadTimer = setTimeout(() => {
      if (current !== generation) return
      loading.value = false
      ready.value = false
      error.value = '管理页面加载超时，请检查知识图谱服务后重试。'
    }, 30000)
  } catch (cause) {
    if (current !== generation) return
    error.value = typeof cause === 'string' ? cause : '无法加载管理页面，请检查知识图谱服务地址、API 密钥和服务状态。'
    loading.value = false
  }
}

function handleFrameMessage(event: MessageEvent) {
  if (event.origin !== window.location.origin || event.source !== frame.value?.contentWindow) return
  if (event.data?.type !== 'lightrag-management-ready' && event.data?.type !== 'lightrag-management-error') return
  clearTimeout(loadTimer)
  loading.value = false
  if (event.data.type === 'lightrag-management-error') {
    error.value = '知识图谱连接或登录已失效，请刷新页面；仍无法连接时请检查服务地址和 API 密钥。'
    ready.value = false
  }
}

const frame = ref<HTMLIFrameElement>()
window.addEventListener('message', handleFrameMessage)
watch(() => [props.knowledgeBaseConfigId, props.view], load, { immediate: true })
onBeforeUnmount(() => {
  generation++
  clearTimeout(loadTimer)
  window.removeEventListener('message', handleFrameMessage)
})
</script>

<template>
  <section class="lightrag-management">
    <div class="management-heading">
      <div><h3>{{ name }} · {{ title }}</h3><p>{{ view === 'graph' ? '浏览知识图谱，搜索实体并查看节点与关系详情。' : '上传文档，查看解析、实体抽取和索引进度，处理失败任务。' }}</p></div>
      <AButton :loading="loading" @click="load"><ReloadOutlined />刷新</AButton>
    </div>
    <AAlert v-if="error" type="error" show-icon message="暂时无法打开知识图谱管理页" :description="error" />
    <div v-if="loading" class="management-loading"><ASpin tip="正在连接知识图谱服务…" /></div>
    <iframe v-if="ready" ref="frame" :key="frameUrl" :src="frameUrl" :title="title" class="management-frame" sandbox="allow-scripts allow-same-origin allow-downloads allow-modals" />
  </section>
</template>

<style scoped lang="scss">
.lightrag-management { position: relative; display: flex; flex-direction: column; height: calc(100vh - 90px); min-height: 440px; }
.management-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding-bottom: 12px; }
.management-heading h3 { margin: 0 0 4px; font-size: var(--font-size-xl); font-weight: 600; }
.management-heading p { margin: 0; color: var(--color-text-regular, #606266); }
.management-frame { width: 100%; flex: 1; min-height: 360px; border: 1px solid var(--color-border-light); border-radius: var(--border-radius-lg); background: #fff; }
.management-loading { position: absolute; inset: 70px 0 0; display: flex; justify-content: center; align-items: center; background: var(--color-bg-container, #fff); z-index: 1; }
</style>
