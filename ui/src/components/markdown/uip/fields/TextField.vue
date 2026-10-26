<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-input
      :value="modelValue"
      :placeholder="field.placeholder"
      :disabled="disabled || field.disabled"
      :readonly="field.readonly"
      @update:value="emit('update:modelValue', $event)"
    />
  </a-form-item>
</template>

<script setup lang="ts">
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: string] }>()
</script>
