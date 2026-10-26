<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAccountStore } from '@/stores'
import * as modelApi from '@/api/model'
import { ModelType, type AgentDefinitionVO, type ModelConfigVO } from '@/types'

type ModelResilienceConfig = NonNullable<AgentDefinitionVO['modelResilienceConfig']>
const props = defineProps<{
  modelValue?: ModelResilienceConfig | null
  modelConfigId?: string
  memoryCompressionEnabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: ModelResilienceConfig | null] }>()
const account = useAccountStore()
const models = ref<ModelConfigVO[]>([])
const loading = ref(false)
const error = ref('')
const validationError = ref('')
const options = computed(() => models.value
  .filter(model => model.enabled && model.modelType?.includes(ModelType.CHAT))
  .map(model => ({ label: model.name, value: String(model.id) })))
const fallbackOptions = computed(() => options.value.filter(model => model.value !== props.modelConfigId))

async function loadModels() {
  if (!account.isAdmin) return
  loading.value = true
  try {
    const response = await modelApi.configPage({ page: 1, size: 1000, enabled: true })
    models.value = response.data.data.records || []
    error.value = ''
  } catch {
    error.value = '读取模型列表失败，请重试'
  } finally {
    loading.value = false
  }
}
function update(values: Partial<ModelResilienceConfig>) {
  if (!account.isAdmin) return
  validationError.value = ''
  emit('update:modelValue', { maxAttempts: 3, ...props.modelValue, ...values })
}
function toggle(enabled: boolean) {
  if (!account.isAdmin) return
  validationError.value = ''
  emit('update:modelValue', enabled ? { maxAttempts: 3 } : null)
}
function validate() {
  validationError.value = ''
  if (!account.isAdmin || !props.modelValue) return true
  const { maxAttempts, fallbackModelId } = props.modelValue
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 5) {
    validationError.value = '主模型最大尝试次数必须为 1～5 次'
  } else if (fallbackModelId && fallbackModelId === props.modelConfigId) {
    validationError.value = '备用模型必须与主模型不同'
  }
  return !validationError.value
}
onMounted(() => { void loadModels() })
defineExpose({ validate })
</script>

<template>
  <section v-if="account.isAdmin" v-permission="['system:admin']" class="model-resilience-settings">
    <AFormItem label="模型容错与摘要模型">
      <ASwitch :checked="!!modelValue" @change="(value: unknown) => toggle(!!value)" />
      <span class="ml-sm text-placeholder">自定义重试、备用模型和摘要模型，保存后用于后续运行</span>
    </AFormItem>
    <template v-if="modelValue">
      <AAlert v-if="error || validationError" class="mb-md" type="error" show-icon :message="error || validationError">
        <template v-if="error" #action><AButton size="small" @click="loadModels">重试</AButton></template>
      </AAlert>
      <ARow :gutter="16">
        <ACol :xs="24" :md="8">
          <AFormItem label="主模型最大尝试次数">
            <AInputNumber :value="modelValue.maxAttempts" :min="1" :max="5" :precision="0" style="width: 100%"
              @change="(value: unknown) => update({ maxAttempts: Number(value) || 1 })" />
            <div class="text-placeholder text-xs mt-xs">包含首次调用；仅在输出开始前重试</div>
          </AFormItem>
        </ACol>
        <ACol :xs="24" :md="8">
          <AFormItem label="备用模型">
            <ASelect :value="modelValue.fallbackModelId" :options="fallbackOptions" :loading="loading" allow-clear
              placeholder="可选，默认不切换模型"
              @change="(value: unknown) => update({ fallbackModelId: value ? String(value) : undefined })" />
            <div class="text-placeholder text-xs mt-xs">主模型重试失败后调用一次，需兼容主模型能力与上下文窗口</div>
          </AFormItem>
        </ACol>
        <ACol :xs="24" :md="8">
          <AFormItem label="摘要模型">
            <ASelect :value="modelValue.summaryModelId" :options="options" :loading="loading" allow-clear
              placeholder="可选，默认使用主模型"
              @change="(value: unknown) => update({ summaryModelId: value ? String(value) : undefined })" />
            <div class="text-placeholder text-xs mt-xs">仅用于记忆压缩，需容纳待压缩历史；{{ memoryCompressionEnabled ? '当前已开启记忆压缩' : '开启记忆及记忆压缩后生效' }}</div>
          </AFormItem>
        </ACol>
      </ARow>
    </template>
  </section>
</template>

<style scoped>
.model-resilience-settings { margin-bottom: 24px; padding-bottom: 8px; border-bottom: 1px solid var(--color-border-extra-light, #eef0f3); }
</style>
