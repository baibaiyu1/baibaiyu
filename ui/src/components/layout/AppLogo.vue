<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 应用品牌组件（纯文字品牌标识，不含图形 Logo）
 *
 */
import { BRAND_MARK, BRAND_NAME } from '@/config/brand'

interface Props {
  /** 是否处于收起状态：收起时仅展示品牌标记 */
  collapsed?: boolean
  /** 项目名称，默认取平台全称 */
  projectName?: string
}

withDefaults(defineProps<Props>(), {
  collapsed: false,
  projectName: BRAND_NAME,
})
</script>

<template>
  <div class="app-logo flex items-center gap-sm pl-md cursor-pointer">
    <div v-if="collapsed" class="logo-mark flex items-center justify-center" :title="projectName">
      {{ BRAND_MARK }}
    </div>
    <span v-else class="project-name">{{ projectName }}</span>
  </div>
</template>

<style scoped lang="scss">
.app-logo {
  .logo-mark {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    background: linear-gradient(135deg, #4f6ef7 0%, #7b5cf0 100%);
    color: #fff;
    font-size: 16px;
    font-weight: 600;
    user-select: none;
  }

  .project-name {
    font-size: 18px;
    font-weight: 600;
    color: #1a1a1a;
    white-space: nowrap;
  }
}
</style>
