<script setup lang="ts">
withDefaults(defineProps<{ tone?: 'neutral' | 'accent' | 'warning' }>(), { tone: 'neutral' })
</script>

<template>
  <span class="resource-card-tag" :class="`tone-${tone}`"><slot /></span>
</template>

<style scoped>
.resource-card-tag { display: inline-block; flex-shrink: 0; max-width: 120px; padding: 1px 7px; border-radius: 5px; background: #f4f5f7; color: #737b88; font-size: 11px; line-height: 19px; font-weight: 400; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; vertical-align: middle; }
.tone-accent { background: #f0f4fa; color: #617a9a; }
.tone-warning { background: #faf6ed; color: #9b804b; }
</style>
