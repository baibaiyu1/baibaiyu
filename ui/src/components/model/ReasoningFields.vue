<script setup lang="ts">
import { computed } from 'vue'
import type { ReasoningSettings, ReasoningCapabilities } from '@/types/reasoning'

const props = defineProps<{ modelValue: ReasoningSettings; capabilities?: ReasoningCapabilities; maxTokens?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: ReasoningSettings] }>()
const labels: Record<string, string> = { minimal: '最低', low: '低', medium: '中', high: '高', xhigh: '最高', max: '最高' }
const modes = computed(() => [
  { label: '使用供应商默认', value: 'DEFAULT' },
  { label: '开启推理', value: 'ENABLED', disabled: !props.capabilities?.canEnable },
  { label: '关闭推理', value: 'DISABLED', disabled: !props.capabilities?.canDisable }
])
const efforts = computed(() => [{ label: '自动（high）', value: '' },
  ...(props.capabilities?.efforts || []).map(value => ({ label: `${labels[value] || value}（${value}）`, value }))])
function update(values: Partial<ReasoningSettings>) {
  const next = { ...props.modelValue, ...values }
  if (next.mode !== 'ENABLED') { delete next.effort; delete next.budgetTokens }
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="reasoning-fields">
    <AFormItem label="推理模式">
      <ASelect :value="modelValue.mode" :options="modes" @change="(mode: ReasoningSettings['mode']) => update({ mode })" />
    </AFormItem>
    <AFormItem v-if="modelValue.mode === 'ENABLED' && capabilities?.efforts.length" label="推理强度">
      <ASelect :value="modelValue.effort || ''" :options="efforts" @change="(effort: string) => update({ effort: effort || null })" />
    </AFormItem>
    <AFormItem v-if="modelValue.mode === 'ENABLED' && capabilities?.minBudget != null" label="推理预算（Token）">
      <AInputNumber :value="modelValue.budgetTokens" :min="capabilities.minBudget" :max="Math.min(capabilities.maxBudget || Infinity, maxTokens || Infinity)"
        :precision="0" placeholder="使用模型默认预算" style="width: 100%" @change="(value: number | null) => update({ budgetTokens: value })" />
    </AFormItem>
    <AFormItem label="展示思考内容">
      <ASwitch :checked="modelValue.showThinking" @change="(show: boolean) => update({ showThinking: show })" />
      <div class="text-placeholder text-xs mt-xs">展示供应商实际返回的思考文字或摘要；隐藏展示不会关闭推理。</div>
    </AFormItem>
    <div v-if="capabilities?.hint" class="text-placeholder text-xs">{{ capabilities.hint }}</div>
  </div>
</template>
