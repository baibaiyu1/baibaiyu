<script setup lang="ts">
import { computed, ref } from 'vue'
import ChatImageCard from './ChatImage.vue'
import WorkspaceFilePreview from '@/components/workspace/WorkspaceFilePreview.vue'
import type { ChatImage } from '@/types'
import { useChatImagePreview } from '@/composables/chat/useChatImagePreview'

const props = defineProps<{ images: ChatImage[] }>()
const expanded = ref(false)
const previewVisible = ref(false)
const currentKey = ref('')
const sharedPreview = useChatImagePreview()
const previewImages = computed(() => props.images.filter(image => image.source && !image.error))
const currentIndex = computed({
  get: () => Math.max(0, previewImages.value.findIndex(image => image.key === currentKey.value)),
  set: index => { currentKey.value = previewImages.value[index]?.key ?? '' },
})
const visibleImages = computed(() => expanded.value ? props.images : props.images.slice(0, 6))
function open(image: ChatImage) {
  if (sharedPreview) sharedPreview.open(image, previewImages.value)
  else { currentKey.value = image.key; previewVisible.value = true }
}
</script>

<template>
  <div class="chat-images" aria-label="对话图片">
    <div class="chat-images-grid">
      <ChatImageCard v-for="image in visibleImages" :key="image.key" :image="image" gallery @open="open" />
    </div>
    <button v-if="images.length > 6" type="button" class="chat-images-more" @click="expanded = !expanded">
      {{ expanded ? '收起图片' : `查看全部 ${images.length} 张图片` }}
    </button>
    <WorkspaceFilePreview v-if="previewVisible" v-model:visible="previewVisible"
      v-model:current-index="currentIndex" :images="previewImages" :file-node="null" :session-id="null" />
  </div>
</template>

<style scoped>
.chat-images { width: 100%; }
.chat-images-grid { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px; }
.chat-images-more { margin-top: 8px; padding: 4px 0; border: none; background: transparent; color: #0f74ff; cursor: pointer; }
</style>
