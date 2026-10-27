<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeModelOption } from '@/api/knowledge'

const props = defineProps<{
  value?: string
  usage: string
  models: KnowledgeModelOption[]
  loading?: boolean
  disabled?: boolean
  placeholder?: string
}>()
const emit = defineEmits<{
  'update:value': [value: string | undefined]
  change: [model?: KnowledgeModelOption]
}>()
const options = computed(() => {
  const available = props.models.filter(model => model.usages.includes(props.usage))
    .map(model => ({ value: model.id, label: `${model.providerName} / ${model.name} (${model.modelId})` }))
  if (props.value && !available.some(model => model.value === props.value)) {
    available.push({ value: props.value, label: `当前模型不可用 (${props.value})` })
  }
  return available
})
function change(value?: string) {
  emit('update:value', value)
  emit('change', props.models.find(model => model.id === value))
}
</script>

<template>
  <ASelect :value="value" :options="options" :loading="loading" :disabled="disabled" show-search allow-clear
    option-filter-prop="label" :placeholder="placeholder || '从平台模型管理中选择'" @change="change" />
</template>
