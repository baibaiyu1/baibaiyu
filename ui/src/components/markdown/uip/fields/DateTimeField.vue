<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-date-picker
      show-time
      :value="modelValue ? dayjs(modelValue) : null"
      :placeholder="field.placeholder"
      :disabled="disabled || field.disabled"
      style="width: 100%"
      @change="onChange"
    />
  </a-form-item>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: string] }>()

function onChange(_: unknown, dateStr: string | string[]) {
  emit('update:modelValue', Array.isArray(dateStr) ? (dateStr[0] || '') : dateStr)
}
</script>
