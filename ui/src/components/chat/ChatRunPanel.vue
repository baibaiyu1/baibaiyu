<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  width?: number
  attention?: boolean
}>(), { width: 560 })

const open = defineModel<boolean>('open', { default: false })
const emit = defineEmits<{ (e: 'afterOpenChange', visible: boolean): void }>()
</script>

<template>
  <div class="chat-run-panel">
    <button
      type="button"
      class="run-panel-trigger"
      :class="{ 'is-open': open, 'needs-attention': attention }"
      :aria-label="`查看${title}`"
      aria-haspopup="dialog"
      :aria-expanded="open"
      @click="open = true"
    >
      <span class="trigger-icon" aria-hidden="true"><slot name="icon" /></span>
      <span>{{ title }}</span>
      <span class="trigger-summary"><slot name="summary" /></span>
    </button>
    <ADrawer
      v-model:open="open"
      :title="title"
      destroy-on-close
      :width="`min(${width}px, 100vw)`"
      :body-style="{ padding: '20px' }"
      @after-open-change="emit('afterOpenChange', $event)"
    >
      <template #extra><slot name="extra" /></template>
      <slot />
    </ADrawer>
  </div>
</template>

<style scoped>
.chat-run-panel { min-width: 0; }
.run-panel-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 100%;
  min-height: 32px;
  padding: 5px 9px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--color-text-regular, #64748b);
  font: inherit;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
  cursor: pointer;
  transition: color .18s, background .18s, border-color .18s;
}
.run-panel-trigger:hover, .run-panel-trigger.is-open {
  color: #0f74ff;
  background: #f0f6ff;
  border-color: #dceaff;
}
.run-panel-trigger:focus-visible { outline: 2px solid #0f74ff; outline-offset: 2px; }
.trigger-icon { display: inline-flex; font-size: 14px; }
.trigger-summary { color: #8792a2; font-variant-numeric: tabular-nums; }
.needs-attention .trigger-summary { padding: 0 6px; border-radius: 5px; color: #0f74ff; background: #eaf3ff; }
@media (max-width: 480px) {
  .run-panel-trigger { gap: 5px; padding-inline: 5px; }
}
</style>
