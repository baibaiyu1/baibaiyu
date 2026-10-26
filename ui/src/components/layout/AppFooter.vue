<script setup lang="ts">
/**
 * 应用底部组件
 *
 */
import { BRAND_NAME } from '@/config/brand'

const currentYear = new Date().getFullYear()
</script>

<template>
  <div class="app-footer flex items-center justify-center">
    <div class="footer-content flex flex-col items-center gap-sm">
      <div class="footer-description">
        <p>{{ BRAND_NAME }}</p>
      </div>

      <div class="footer-copyright">
        <p>&copy; {{ currentYear }} {{ BRAND_NAME }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/components/footer' as *;
</style>
