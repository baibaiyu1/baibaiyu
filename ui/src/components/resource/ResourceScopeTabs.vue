<script setup lang="ts">
import { onMounted } from 'vue'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'

const modelValue = defineModel<string>('value', { default: 'all' })
const store = useResourceSpaceStore()
const scopeOptions = [
  { label: '全部', value: 'all' },
  { label: '共享给我', value: 'shared' },
  { label: '我的收藏', value: 'favorite' }
]
onMounted(() => { void store.load(true).catch(error => console.warn('加载资源空间失败', error)) })
</script>

<template>
  <div class="resource-space-controls">
    <ASegmented v-model:value="modelValue" :options="scopeOptions" />
    <span class="creation-hint">{{ store.canCreate ? `新建到：${store.creationLabel}` : '当前空间可浏览，创建请联系管理员' }}</span>
  </div>
</template>

<style scoped>
.resource-space-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.creation-hint { font-size: 12px; color: var(--color-text-secondary); }
</style>
