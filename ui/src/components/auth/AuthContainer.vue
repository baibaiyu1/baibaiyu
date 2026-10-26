<script setup lang="ts">
/**
 * 认证页面容器：居中的品牌标题、表单与页脚
 */
import { BRAND_MARK, BRAND_NAME } from '@/config/brand'

const currentYear = new Date().getFullYear()
</script>

<template>
  <div class="auth-page">
    <main class="auth-main">
      <header class="auth-brand-header">
        <span class="auth-brand-mark" aria-hidden="true">{{ BRAND_MARK }}</span>
        <h1 class="auth-brand-name">{{ BRAND_NAME }}</h1>
      </header>
      <section class="auth-card">
        <slot />
      </section>
    </main>
    <footer class="auth-brand-footer">
      &copy; {{ currentYear }} {{ BRAND_NAME }}
    </footer>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/auth' as *;
</style>
