<template>
  <div class="platform-loading" :class="sizeClass" role="status" aria-label="加载中">
    <div class="logo-container">
      <svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <circle cx="50" cy="50" r="36" fill="none" stroke="#e5e7eb" stroke-width="8" />
        <circle class="loading-ring" cx="50" cy="50" r="36" fill="none"
                stroke="currentColor" stroke-width="8" stroke-linecap="round"
                stroke-dasharray="65 162" />
      </svg>
    </div>
    <div v-if="tip" class="loading-tip">{{ tip }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  /** 加载提示文本 */
  tip?: string
  /** 加载动画尺寸 */
  size?: 'small' | 'default' | 'large'
  /** 是否全屏显示 */
  fullscreen?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  fullscreen: false
})

const sizeClass = computed(() => ({
  [`size-${props.size}`]: true,
  'fullscreen': props.fullscreen
}))
</script>

<style scoped lang="scss">
.platform-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
}

.platform-loading.fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  background-color: rgba(255, 255, 255, 0.9);
}

.logo-container {
  width: 64px;
  height: 64px;
}

.logo-container svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}

.platform-loading.size-small .logo-container {
  width: 32px;
  height: 32px;
}

.platform-loading.size-large .logo-container {
  width: 96px;
  height: 96px;
}

.loading-tip {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.65);
  text-align: center;
}

.platform-loading.size-small .loading-tip {
  font-size: 12px;
}

.platform-loading.size-large .loading-tip {
  font-size: 16px;
}

.logo-container { color: #4f6ef7; }

.loading-ring {
  transform-origin: 50% 50%;
  animation: loadingRotate 0.9s linear infinite;
}

@keyframes loadingRotate {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .loading-ring { animation: none; }
}
</style>
