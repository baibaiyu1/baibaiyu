<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { DownloadOutlined } from '@ant-design/icons-vue'
import WorkspaceFilePreview from '@/components/workspace/WorkspaceFilePreview.vue'
import { acquireImageUrl, downloadImage } from '@/utils/chat/image'
import { useChatImagePreview } from '@/composables/chat/useChatImagePreview'
import type { ChatImage } from '@/types'

const props = defineProps<{ image: ChatImage; inline?: boolean; gallery?: boolean; previewImages?: ChatImage[] }>()
const emit = defineEmits<{ open: [image: ChatImage] }>()
const url = ref('')
const failed = ref(false)
const previewVisible = ref(false)
const sharedPreview = useChatImagePreview()
let request = 0
let lease: ReturnType<typeof acquireImageUrl> | null = null
const sourceKey = computed(() => JSON.stringify(props.image.source))

function release() {
  lease?.release()
  lease = null
  url.value = ''
}

watch(sourceKey, async () => {
  const version = ++request
  release()
  failed.value = false
  const source = props.image.source
  if (!source || props.image.error) return
  lease = acquireImageUrl(source)
  try {
    const loaded = await lease.url
    if (request !== version) return
    url.value = loaded
  } catch { if (request === version) failed.value = true }
}, { immediate: true })

onUnmounted(() => { request++; release() })

function open() {
  if (!props.image.source || props.image.error) return
  if (props.gallery) emit('open', props.image)
  else if (sharedPreview) sharedPreview.open(props.image, props.previewImages)
  else previewVisible.value = true
}

async function download() {
  try { if (props.image.source) await downloadImage(props.image.source, props.image.fileName) }
  catch { failed.value = true }
}
</script>

<template>
  <figure class="chat-image" :class="{ 'is-inline': inline }" :data-image-key="image.key">
    <button class="chat-image-content" type="button" :aria-label="`预览 ${image.fileName}`"
      :disabled="!image.source || !!image.error" @click="open">
      <img v-if="url && !failed" :src="url" :alt="image.fileName" loading="lazy" @error="failed = true" />
      <span v-else class="chat-image-status">{{ image.error || (failed ? '图片加载失败，可点击预览重试' : '图片加载中…') }}</span>
    </button>
    <figcaption class="chat-image-caption">
      <span class="chat-image-name" :title="image.fileName">{{ image.fileName }}</span>
      <button v-if="image.source && !image.error" type="button" class="chat-image-download"
        :aria-label="`下载 ${image.fileName}`" title="下载图片" @click="download"><DownloadOutlined /></button>
    </figcaption>
    <WorkspaceFilePreview v-if="previewVisible" v-model:visible="previewVisible" :image="image"
      :file-node="null" :session-id="null" />
  </figure>
</template>

<style scoped lang="scss">
.chat-image { display: inline-flex; flex-direction: column; width: min(100%, 248px); max-width: 100%; min-width: 0;
  margin: 0; overflow: hidden; border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; }
.chat-image-content { display: flex; align-items: center; justify-content: center; width: 100%; height: 158px;
  padding: 0; border: 0; background: #f7f8fa; cursor: zoom-in; }
.chat-image-content:disabled { cursor: default; }
.chat-image-content img { display: block; max-width: 100%; max-height: 100%; object-fit: contain; }
.chat-image-status { padding: 24px 16px; color: #8c8c8c; font-size: 13px; }
.chat-image-caption { display: flex; align-items: center; gap: 6px; padding: 5px 9px; font-size: 12px; }
.chat-image-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-image-download { padding: 2px 4px; border: 0; background: transparent; color: #666; cursor: pointer; }
.is-inline { margin: 8px 0; vertical-align: top; }
</style>
