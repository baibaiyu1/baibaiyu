<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-select
      :value="modelValue"
      :placeholder="field.placeholder"
      :disabled="disabled || field.disabled"
      :mode="field.multiple ? 'multiple' : undefined"
      style="width: 100%"
      @update:value="emit('update:modelValue', $event)"
    >
      <a-select-option
        v-for="opt in field.options"
        :key="String(opt.value)"
        :value="opt.value"
        :disabled="opt.disabled"
      >
        {{ opt.label }}
      </a-select-option>
    </a-select>
  </a-form-item>
</template>

<script setup lang="ts">
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: unknown; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: unknown] }>()
</script>
