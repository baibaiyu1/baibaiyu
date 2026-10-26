<template>
  <a-form-item :label="field.label" :required="field.required" :help="field.helpText" class="apip-field-item">
    <a-switch
      :checked="modelValue"
      :disabled="disabled || field.disabled"
      @update:checked="emit('update:modelValue', $event)"
    />
  </a-form-item>
</template>

<script setup lang="ts">
import type { FormField } from '../types'

defineProps<{ field: FormField; modelValue?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()
</script>
