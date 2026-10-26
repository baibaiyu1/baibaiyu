<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-radio-group
      :value="modelValue"
      :disabled="disabled || field.disabled"
      @update:value="emit('update:modelValue', $event)"
    >
      <a-radio
        v-for="opt in field.options"
        :key="String(opt.value)"
        :value="opt.value"
        :disabled="opt.disabled"
      >
        {{ opt.label }}
      </a-radio>
    </a-radio-group>
  </a-form-item>
</template>

<script setup lang="ts">
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: unknown; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: unknown] }>()
</script>
