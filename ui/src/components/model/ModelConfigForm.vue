/**
 * 模型配置表单组件
 *
 */
<script setup lang="ts">
import { ref, watch, computed, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { EyeOutlined } from '@ant-design/icons-vue'
import type { ModelConfigVO, ModelConfig, ModelProviderVO } from '@/types'
import { ModelType, ModelProviderType } from '@/types'
import * as modelApi from '@/api/model'
import ExtendConfigEditor, { type ExtendConfigData } from './ExtendConfigEditor.vue'
import ContextWindowHint from './ContextWindowHint.vue'
import MaxTokensHint from './MaxTokensHint.vue'
import ReasoningFields from './ReasoningFields.vue'
import type { ReasoningCapabilities, ReasoningSettings } from '@/types/reasoning'
import { cleanReasoningExtensions, defaultReasoning, modelReasoning } from '@/utils/modelReasoning'

/**
 * Props定义
 */
const props = defineProps<{
  visible: boolean
  providerId: string
  providerType?: string
  data?: ModelConfigVO
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: []
}>()

const formRef = ref()
const loading = ref<boolean>(false)
const reasoning = ref<ReasoningSettings>(defaultReasoning())
const reasoningProfile = ref('AUTO')
const capabilities = ref<ReasoningCapabilities>()
const capabilitiesLoading = ref(false)

const formData = ref<{
  used?: string[]
  name: string
  modelId: string
  apiType: 'CHAT_COMPLETIONS' | 'RESPONSES'
  responsesSampling: boolean
  modelType: ModelType[]
  description: string
  streaming: boolean
  thinking: boolean
  contextWindow: number
  maxTokens: number
  temperature: number
  topP: number
  topK: number
  repeatPenalty: number
  seed: string
  extendConfig: ExtendConfigData | null
}>({
  name: '',
  modelId: '',
  apiType: 'CHAT_COMPLETIONS',
  responsesSampling: false,
  modelType: [ModelType.CHAT],
  description: '',
  streaming: true,
  thinking: false,
  contextWindow: 200000,
  maxTokens: 8192,
  temperature: 0.7,
  topP: 0.9,
  topK: 50,
  repeatPenalty: 1.0,
  seed: '',
  extendConfig: null
})

const isEdit = computed(() => !!props.data?.id)
const isResponses = computed(() => props.providerType === 'OPEN_AI' && formData.value.apiType === 'RESPONSES')
watch(() => [props.visible, props.providerId, formData.value.modelId, formData.value.apiType, reasoningProfile.value],
  (_value, _previous, onCleanup) => {
    let stale = false
    capabilities.value = undefined
    capabilitiesLoading.value = !!props.visible && !!props.providerId && !!formData.value.modelId
    onCleanup(() => { stale = true; clearTimeout(timer) })
    const timer = setTimeout(async () => {
      if (!props.visible || !props.providerId || !formData.value.modelId) { capabilitiesLoading.value = false; return }
      capabilitiesLoading.value = true
      try {
        const result = await modelApi.reasoningCapabilities({ providerId: props.providerId, modelId: formData.value.modelId,
          apiType: formData.value.apiType, profile: reasoningProfile.value })
        if (!stale) capabilities.value = result.data.data
      } catch { if (!stale) capabilities.value = undefined }
      finally { if (!stale) capabilitiesLoading.value = false }
    }, 200)
  })

/**
 * 模型类型选项
 */
const modelTypeOptions = [
  { label: '文本模型', value: ModelType.CHAT },
  { label: '图像模型', value: ModelType.IMAGE },
  { label: '音频模型', value: ModelType.AUDIO },
  { label: '视频模型', value: ModelType.VIDEO }
]

watch(
  () => props.visible,
  (newVal) => {
    if (newVal) {
      if (props.data) {
        const ec = props.data.extendConfig as ExtendConfigData | null
        reasoning.value = modelReasoning(props.data)
        reasoningProfile.value = ec?.reasoningProfile || 'AUTO'
        capabilities.value = props.data.reasoningCapabilities
        formData.value = {
          used: props.data.used,
          name: props.data.name,
          modelId: props.data.modelId,
          apiType: props.data.apiType || 'CHAT_COMPLETIONS',
          responsesSampling: props.data.responsesSampling ?? false,
          modelType: Array.isArray(props.data.modelType) ? props.data.modelType : [props.data.modelType],
          description: props.data.description,
          streaming: props.data.streaming,
          thinking: props.data.thinking,
          contextWindow: props.data.contextWindow,
          maxTokens: props.data.maxTokens,
          temperature: props.data.temperature,
          topP: props.data.topP,
          topK: props.data.topK,
          repeatPenalty: props.data.repeatPenalty,
          seed: props.data.seed || '',
          extendConfig: cleanReasoningExtensions(ec)
        }
      } else {
        resetForm()
      }
    }
  }
)

/**
 * 表单验证规则
 */
const rules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 100, message: '名称长度不能超过100个字符', trigger: 'blur' }
  ],
  modelId: [
    { required: true, message: '请输入模型ID', trigger: 'blur' },
    { max: 100, message: '模型ID长度不能超过100个字符', trigger: 'blur' }
  ],
  modelType: [
    { required: true, type: 'array', min: 1, message: '请至少选择一个模型类型', trigger: 'change' }
  ],
  description: [
    { required: true, message: '请选择模型类型', trigger: 'blur' },
    { max: 300, message: '描述长度不能超过300个字符', trigger: 'blur' }
  ],
  contextWindow: [
    { required: true, message: '请输入上下文窗口大小', trigger: 'blur' }
  ],
  maxTokens: [
    { required: true, message: '请输入最大输出 Token 数', trigger: 'blur' }
  ],
  temperature: [
    { required: true, message: '请设置温度参数', trigger: 'blur' }
  ],
  topP: [
    { required: true, message: '请设置Top P参数', trigger: 'blur' }
  ],
  topK: [
    { required: true, message: '请设置Top K参数', trigger: 'blur' }
  ],
  repeatPenalty: [
    { required: true, message: '请设置重复惩罚参数', trigger: 'blur' }
  ]
}

/**
 * 重置表单
 */
function resetForm() {
  reasoning.value = defaultReasoning()
  reasoningProfile.value = 'AUTO'
  capabilities.value = undefined
  formData.value = {
    name: '',
    modelId: '',
    apiType: 'CHAT_COMPLETIONS',
    responsesSampling: false,
    modelType: [ModelType.CHAT],
    description: '',
    streaming: true,
    thinking: false,
    contextWindow: 200000,
    maxTokens: 8192,
    temperature: 0.7,
    topP: 0.9,
    topK: 50,
    repeatPenalty: 1.0,
    seed: '',
    extendConfig: null
  }
  formRef.value?.resetFields()
}

/**
 * 处理提交
 */
async function handleSubmit() {
  try {
    await formRef.value?.validate()
    if (capabilitiesLoading.value || !capabilities.value) {
      message.warning('请等待模型推理能力加载完成')
      return
    }
    loading.value = true

    const entity: ModelConfig = {
      providerId: props.providerId,
      name: formData.value.name,
      modelId: formData.value.modelId,
      apiType: props.providerType === 'OPEN_AI' ? formData.value.apiType : 'CHAT_COMPLETIONS',
      responsesSampling: formData.value.responsesSampling,
      modelType: formData.value.modelType,
      description: formData.value.description,
      streaming: formData.value.streaming,
      thinking: formData.value.thinking,
      contextWindow: formData.value.contextWindow,
      maxTokens: formData.value.maxTokens,
      temperature: formData.value.temperature,
      topP: formData.value.topP,
      topK: formData.value.topK,
      repeatPenalty: formData.value.repeatPenalty,
      seed: formData.value.seed,
      extendConfig: { ...(formData.value.extendConfig || { headers: {}, queryParams: {}, bodyParams: {} }),
        reasoning: reasoning.value, reasoningProfile: reasoningProfile.value }
    } as ModelConfig

    if (isEdit.value && props.data) {
      entity.id = props.data.id as string
      await modelApi.configUpdate(entity)
      message.success('更新成功')
    } else {
      await modelApi.configSave(entity)
      message.success('创建成功')
    }

    emit('success')
    handleCancel()
  } catch (error) {
    console.error('提交失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 处理取消
 */
function handleCancel() {
  emit('update:visible', false)
  resetForm()
}

/**
 * 获取供应商类型标签
 */
function getProviderTypeLabel(type: string): string {
  const map: Record<string, string> = {
    [ModelProviderType.DASH_SCOPE]: 'DashScope',
    [ModelProviderType.OPEN_AI]: 'OpenAI',
    [ModelProviderType.ANTHROPIC]: 'Anthropic',
    [ModelProviderType.GEMINI]: 'Gemini',
    [ModelProviderType.OLLAMA]: 'Ollama',
    [ModelProviderType.ORCA_ROUTER]: 'OrcaRouter'
  }
  return map[type] || type
}

/**
 * 查看供应商详情
 */
async function handleViewProvider() {
  if (!props.providerId) {
    message.warning('供应商ID不存在')
    return
  }

  const response = await modelApi.providerDetail(props.providerId)
  const data: ModelProviderVO = response.data.data

  Modal.info({
    title: '供应商详情',
    closable: true,
    icon: null,
    footer: null,
    width: 600,
    content: h('div', { style: { maxHeight: '600px', overflowY: 'auto' } }, [
      h('p', {}, [h('strong', '供应商类型: '), getProviderTypeLabel(data.type)]),
      h('p', {}, [h('strong', '名称: '), data.name]),
      h('p', {}, [h('strong', '描述: '), data.description]),
      h('p', {}, [h('strong', 'Base URL: '), data.baseUrl]),
      h('p', {}, [h('strong', '认证类型: '), data.authType === 'CONFIG' ? '直接配置' : '环境变量']),
      ...(data.authType === 'CONFIG' ? [
        h('p', {}, [h('strong', 'API密钥: '), '********'])
      ] : [
        h('p', {}, [h('strong', '环境变量名: '), data.envVarName])
      ]),
      h('p', {}, [h('strong', '是否启用: '), data.enabled ? '是' : '否']),
      h('p', {}, [h('strong', '创建时间: '), data.createdAt]),
      h('p', {}, [h('strong', '更新时间: '), data.updatedAt])
    ])
  })
}
</script>

<template>
  <AppModal
    :open="visible"
    :title="isEdit ? '编辑模型配置' : '新增模型配置'"
    :confirm-loading="loading"
    destroyOnClose
    @ok="handleSubmit"
    @cancel="handleCancel"
  >
    <AForm ref="formRef" :model="formData" :rules="rules" layout="vertical">
      <div class="form-section">
        <AFormItem label="关联智能体" v-if="isEdit">
          <div class="code-wrapper ">
            {{ formData?.used?.join('、') || '无' }}
          </div>
        </AFormItem>
        <div class="section-title">基础信息</div>
        <AFormItem label="名称" name="name">
          <AInput v-model:value="formData.name" placeholder="请输入模型名称">
            <template #suffix>
              <EyeOutlined
                class="view-icon"
                title="查看供应商详情"
                @click="handleViewProvider"
              />
            </template>
          </AInput>
        </AFormItem>

        <AFormItem label="模型ID" name="modelId">
          <AInput v-model:value="formData.modelId" placeholder="请输入模型ID，如: gpt-4" />
        </AFormItem>

        <AFormItem v-if="providerType === 'OPEN_AI'" label="请求协议" name="apiType">
          <ASelect v-model:value="formData.apiType" :options="[
            { label: 'Chat Completions', value: 'CHAT_COMPLETIONS' },
            { label: 'Responses', value: 'RESPONSES' }
          ]" />
          <div class="text-placeholder text-xs mt-xs">按供应商支持的接口选择，保存后可使用“测试连接”验证。</div>
        </AFormItem>
        <template v-if="isResponses">
          <AFormItem label="发送温度与 Top P" name="responsesSampling">
            <ASwitch v-model:checked="formData.responsesSampling" />
            <span class="text-placeholder text-xs"> 默认关闭；仅在模型支持采样参数时开启。</span>
          </AFormItem>
        </template>

        <AFormItem label="模型类型" name="modelType">
          <ASelect
            v-model:value="formData.modelType"
            mode="multiple"
            placeholder="请选择模型类型"
          >
            <ASelectOption v-for="opt in modelTypeOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </ASelectOption>
          </ASelect>
        </AFormItem>

        <AFormItem label="描述" name="description">
          <ATextarea
            v-model:value="formData.description"
            placeholder="请输入模型描述"
            :rows="2"
          />
        </AFormItem>
      </div>

      <div class="form-section">
        <div class="section-title">功能开关</div>

        <ARow :gutter="24">
          <ACol :span="12">
            <AFormItem label="流式输出" name="streaming">
              <ASwitch v-model:checked="formData.streaming" />
            </AFormItem>
          </ACol>
        </ARow>
      </div>

      <div class="form-section">
        <div class="section-title">参数配置</div>

        <ARow :gutter="16">
          <ACol :span="12">
            <AFormItem name="contextWindow">
              <template #label>
                <span>上下文窗口</span>
                <ContextWindowHint v-model="formData.contextWindow" />
              </template>
              <AInputNumber
                v-model:value="formData.contextWindow"
                :min="1"
                :max="1000000"
                style="width: 100%"
                placeholder="请输入上下文窗口大小"
              />
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem name="maxTokens">
              <template #label>
                <span>最大输出 Token 数</span>
                <MaxTokensHint v-model="formData.maxTokens" />
              </template>
              <AInputNumber
                v-model:value="formData.maxTokens"
                :min="1"
                :max="1000000"
                style="width: 100%"
                placeholder="请输入最大输出 Token 数"
              />
            </AFormItem>
          </ACol>
        </ARow>

        <AFormItem v-if="!isResponses || formData.responsesSampling" label="温度 (Temperature)" name="temperature">
          <ARow :gutter="16">
            <ACol :span="16">
              <ASlider
                v-model:value="formData.temperature"
                :min="0"
                :max="2"
                :step="0.1"
              />
            </ACol>
            <ACol :span="8">
              <AInputNumber
                v-model:value="formData.temperature"
                :min="0"
                :max="2"
                :step="0.1"
                style="width: 100%"
              />
            </ACol>
          </ARow>
        </AFormItem>

        <AFormItem v-if="!isResponses || formData.responsesSampling" label="Top P" name="topP">
          <ARow :gutter="16">
            <ACol :span="16">
              <ASlider
                v-model:value="formData.topP"
                :min="0"
                :max="1"
                :step="0.01"
              />
            </ACol>
            <ACol :span="8">
              <AInputNumber
                v-model:value="formData.topP"
                :min="0"
                :max="1"
                :step="0.01"
                style="width: 100%"
              />
            </ACol>
          </ARow>
        </AFormItem>

        <AFormItem v-if="!isResponses" label="Top K" name="topK">
          <AInputNumber
            v-model:value="formData.topK"
            :min="1"
            :max="1000"
            style="width: 100%"
            placeholder="请输入Top K值"
          />
        </AFormItem>

        <AFormItem v-if="!isResponses" label="重复惩罚 (Repeat Penalty)" name="repeatPenalty">
          <ARow :gutter="16">
            <ACol :span="16">
              <ASlider
                v-model:value="formData.repeatPenalty"
                :min="0"
                :max="2"
                :step="0.1"
              />
            </ACol>
            <ACol :span="8">
              <AInputNumber
                v-model:value="formData.repeatPenalty"
                :min="0"
                :max="2"
                :step="0.1"
                style="width: 100%"
              />
            </ACol>
          </ARow>
        </AFormItem>

        <AFormItem v-if="!isResponses" label="随机种子 (Seed)" name="seed">
          <AInputNumber
            v-model:value="formData.seed"
            style="width: 100%"
            placeholder="请输入随机种子，留空表示随机" />
        </AFormItem>
      </div>

      <div class="form-section">
        <div class="section-title">默认推理设置</div>
        <AFormItem v-if="providerType === 'OPEN_AI' || providerType === 'ORCA_ROUTER'" label="推理兼容类型">
          <ASelect v-model:value="reasoningProfile" :options="[
            { label: '自动识别', value: 'AUTO' }, { label: 'OpenAI 推理模型', value: 'OPENAI' },
            { label: 'DeepSeek', value: 'DEEPSEEK' }, { label: 'Qwen（Chat Completions）', value: 'QWEN' },
            { label: '普通模型', value: 'NONE' }
          ]" />
          <div class="text-placeholder text-xs mt-xs">代理服务请选择实际兼容类型。智能体选择独立配置后，不再跟随这里的默认值。</div>
        </AFormItem>
        <ReasoningFields v-model="reasoning" :capabilities="capabilities" :max-tokens="formData.maxTokens" />
      </div>

      <div class="form-section">
        <div class="section-title">扩展配置</div>
        <div class="text-placeholder text-xs mb-sm">推理开关、强度和预算请在上方设置，重复的原始推理参数会在保存时被拒绝。</div>
        <AFormItem label="">
          <ExtendConfigEditor
            v-model="formData.extendConfig"
            :show-fixed-system-message="providerType === 'OPEN_AI' && !isResponses"
          />
        </AFormItem>
      </div>
    </AForm>
  </AppModal>
</template>

<style scoped lang="scss">
.form-section {
  margin-bottom: var(--spacing-lg);

  .section-title {
    font-size: var(--font-size-base);
    font-weight: 600;
    color: var(--color-text-primary);
    margin-bottom: var(--spacing-md);
    padding-bottom: var(--spacing-xs);
    border-bottom: 1px solid var(--color-border-light);
  }
}

.view-icon {
  cursor: pointer;
  color: var(--color-primary);
  font-size: 16px;

  &:hover {
    color: var(--color-primary-hover);
  }
}
</style>
