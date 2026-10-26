<script setup lang="ts">
import { computed } from 'vue'
import { DownloadOutlined } from '@ant-design/icons-vue'
import MediaIcon from '@/components/common/MediaIcon.vue'
import ChatImageCard from './ChatImage.vue'
import * as workspaceApi from '@/api/workspace'
import { artifactImage, isImageFile } from '@/utils/chat/image'
import type { ChatArtifactVO } from '@/types'

const props = defineProps<{ artifact: ChatArtifactVO; sessionId: string | null }>()
const emit = defineEmits<{ open: [artifact: ChatArtifactVO] }>()
const extension = computed(() => (props.artifact.extension ?? '').toLowerCase())
const image = computed(() => artifactImage(props.artifact, props.sessionId))
const readableSize = computed(() => {
  const bytes = props.artifact.sizeBytes
  if (bytes == null || !Number.isFinite(bytes)) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
})
async function handleDownload(event: MouseEvent) {
  event.stopPropagation()
  if (!props.sessionId) return
  try {
    const response = await workspaceApi.download(props.sessionId, props.artifact.filePath)
    const url = URL.createObjectURL(response.data as Blob)
    const link = document.createElement('a')
    link.href = url
    link.download = props.artifact.fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) { console.error('产物下载失败:', error) }
}
</script>

<template>
  <ChatImageCard v-if="isImageFile(extension)" :image="image" />
  <div v-else class="artifact-card" :title="artifact.filePath" role="button" tabindex="0"
    @click="emit('open', artifact)" @keydown.enter="emit('open', artifact)">
    <MediaIcon :type="extension || 'FILE'" :size="18" />
    <span class="artifact-card-name">{{ artifact.fileName }}</span>
    <span v-if="readableSize" class="artifact-card-size">{{ readableSize }}</span>
    <button type="button" class="artifact-card-download" title="下载" @click="handleDownload"><DownloadOutlined /></button>
  </div>
</template>

<style scoped lang="scss">
.artifact-card { display: flex; align-items: center; gap: 8px; max-width: 100%; width: fit-content;
  min-width: 180px; padding: 6px 8px; border: 1px solid #e5e5e5; border-radius: 8px; background: #fff; cursor: pointer;
  &:hover { border-color: #0f74ff; background: rgba(15, 116, 255, 0.03); }
  &:focus-visible { outline: 2px solid rgba(15, 116, 255, 0.5); outline-offset: 1px; } }
.artifact-card-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: #262626; }
.artifact-card-size { flex-shrink: 0; font-size: 12px; color: #bfbfbf; }
.artifact-card-download { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px;
  flex-shrink: 0; padding: 0; border: none; border-radius: 4px; background: transparent; color: #bfbfbf; cursor: pointer;
  &:hover { background: rgba(15, 116, 255, 0.1); color: #0f74ff; } }
</style>
