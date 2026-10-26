<script setup lang="ts">
import { computed } from 'vue'
import ReasoningFields from '@/components/model/ReasoningFields.vue'
import type { ModelConfigVO } from '@/types'
import type { ReasoningSettings } from '@/types/reasoning'
import { cleanReasoningExtensions, describeReasoning, modelReasoning, withoutReasoningOverride } from '@/utils/modelReasoning'
import type { ExtendConfigData } from '@/components/model/ExtendConfigEditor.vue'

const props = defineProps<{ model?: ModelConfigVO; modelValue: Record<string, unknown> | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown> | null] }>()
const independent = computed(() => !!props.modelValue?.reasoning)
const settings = computed(() => independent.value ? props.modelValue!.reasoning as ReasoningSettings : modelReasoning(props.model))
const effectiveText = computed(() => `${describeReasoning(settings.value, props.model?.reasoningCapabilities)} · 来自${independent.value ? '智能体配置' : '模型默认配置'}`)
function update(reasoning: ReasoningSettings) {
  const next: Record<string, unknown> = { ...props.modelValue, reasoning: { ...reasoning } }
  if (next.extendConfig) next.extendConfig = cleanReasoningExtensions(next.extendConfig as ExtendConfigData)
  emit('update:modelValue', next)
}
function toggle(enabled: boolean) {
  if (enabled) update({ ...settings.value })
  else emit('update:modelValue', withoutReasoningOverride(props.modelValue))
}
function validate() {
  if (!independent.value) return true
  const caps = props.model?.reasoningCapabilities
  if (!caps) return false
  const value = settings.value
  const maxTokens = Number(props.modelValue?.maxTokens || props.model?.maxTokens)
  const budget = value.budgetTokens ?? (caps.profile === 'ANTHROPIC' && value.mode === 'ENABLED' ? 1024 : null)
  return !(value.mode === 'ENABLED' && !caps.canEnable || value.mode === 'DISABLED' && !caps.canDisable
    || value.effort && !caps.efforts.includes(value.effort)
    || budget != null && maxTokens > 0 && (budget > maxTokens || caps.profile === 'ANTHROPIC' && budget === maxTokens)
    || value.budgetTokens != null && (caps.minBudget == null || value.budgetTokens < caps.minBudget || value.budgetTokens > (caps.maxBudget || Infinity)))
}
defineExpose({ validate })
</script>

<template>
  <AForm layout="vertical">
    <AFormItem label="独立推理配置">
      <ASwitch :checked="independent" :disabled="!model" @change="(enabled: boolean) => toggle(enabled)" />
      <div class="text-placeholder text-xs mt-xs">关闭时继承模型配置；开启时保存当前推理设置，之后不随模型默认值变化。采样参数单独覆盖。</div>
    </AFormItem>
    <AAlert :message="effectiveText" type="info" style="margin-bottom: 16px" />
    <ReasoningFields v-if="independent" :model-value="settings" :capabilities="model?.reasoningCapabilities"
      :max-tokens="Number(modelValue?.maxTokens || model?.maxTokens)" @update:model-value="update" />
    <div v-if="!model" class="text-placeholder">请先选择模型。</div>
  </AForm>
</template>
