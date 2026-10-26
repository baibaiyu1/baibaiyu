<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 卡片容器
 *
 * 统一「标题 + 说明 + 右上操作 + 内容」的卡片结构，
 * 使各配置分区在编排页中以卡片瀑布形式呈现。
 *
 * @component
 */
import type { Component } from 'vue'

defineProps<{
  /** 卡片标题 */
  title: string
  /** 卡片说明 */
  description?: string
  /** 标题图标 */
  icon?: Component
  /** 内容区是否去除内边距（用于内嵌自带边距的子页面） */
  flush?: boolean
}>()
</script>

<template>
  <section class="agstudio-card">
    <header class="agstudio-card__head">
      <div class="agstudio-card__heading">
        <span v-if="icon" class="agstudio-card__icon">
          <component :is="icon" />
        </span>
        <div>
          <h3 class="agstudio-card__title">{{ title }}</h3>
          <p v-if="description" class="agstudio-card__desc">{{ description }}</p>
        </div>
      </div>
      <div class="agstudio-card__action">
        <slot name="action" />
      </div>
    </header>
    <div class="agstudio-card__body" :class="{ 'agstudio-card__body--flush': flush }">
      <slot />
    </div>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-card.scss' as *;
</style>
