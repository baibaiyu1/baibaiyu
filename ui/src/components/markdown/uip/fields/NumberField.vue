<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-input-number
      :value="modelValue"
      :placeholder="field.placeholder"
      :disabled="disabled || field.disabled"
      :readonly="field.readonly"
      style="width: 100%"
      @update:value="emit('update:modelValue', $event)"
    />
  </a-form-item>
</template>

<script setup lang="ts">
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: number; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: number | undefined] }>()
</script>
