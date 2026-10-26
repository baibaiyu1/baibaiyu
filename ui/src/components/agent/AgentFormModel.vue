/**
 * 智能体模型与提示词表单组件
 *
 */
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { RouteNames } from '@/router/constants.ts'
import SmartCodeEditor from '@/components/editor/SmartCodeEditor.vue'
import ExtendConfigEditor, { type ExtendConfigData } from '@/components/model/ExtendConfigEditor.vue'
import ContextWindowHint from '@/components/model/ContextWindowHint.vue'
import MaxTokensHint from '@/components/model/MaxTokensHint.vue'
import * as modelApi from '@/api/model'
import * as promptApi from '@/api/prompt'
import type { ModelProviderVO, ModelConfigVO, SystemPromptTemplateVO } from '@/types'
import type { StudioSummary } from '@/components/agent/studio/summary'
import AgentReasoningSettings from './AgentReasoningSettings.vue'
import { cleanReasoningExtensions, hasSamplingOverride, reasoningOnly } from '@/utils/modelReasoning'
/**
 * Props定义
 */
const props = withDefaults(defineProps<{
  modelValue: {
    modelConfigId: string
    modelParamsOverride: Record<string, unknown> | null
    systemPromptTemplateId: string
    followTemplate: boolean
    systemPrompt: string
  }
  /**
   * 仅渲染「系统提示词模板 + 提示词」部分。
   * 编排页把「选模型」与「模型参数覆盖」搬到顶栏（与参照页一致），故此处跳过它们。
   */
  promptOnly?: boolean
}>(), {
  promptOnly: false
})

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:modelValue': [value: typeof props.modelValue]
  /** 候选资源（模型 / 提示词模板）加载完成，通知编排页刷新「已选资源」概览 */
  ready: []
}>()

const formRef = ref()
const reasoningFormRef = ref()
const selectedReasoningModel = computed(() => allModels.value.find(model => String(model.id) === String(formData.value.modelConfigId)))
const router = useRouter()
const loading = ref(false)

const modelProviders = ref<ModelProviderVO[]>([])
const allModels = ref<ModelConfigVO[]>([])
const promptCategories = ref<string[]>([])
const allPrompts = ref<SystemPromptTemplateVO[]>([])

const selectedProviderId = ref<string>('')
const selectedPromptCategory = ref<string>('')
const showModelParamsOverride = ref(false)
const modelParamsForm = ref<Record<string, unknown>>({})

/**
 * 表单数据
 */
const formData = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

/**
 * 模型提供商选项
 */
const providerOptions = computed(() => {
  return modelProviders.value.map(p => ({
    label: p.name,
    value: p.id
  }))
})

/**
 * 当前提供商的模型列表
 */
const currentModels = computed(() => {
  if (!selectedProviderId.value) {
    const model = allModels.value.filter(p => p.id === formData.value.modelConfigId)[0] || null
    if (model) {
      selectedProviderId.value = model.providerId
    } else {
      selectedProviderId.value = allModels.value[0]?.providerId || ''
    }
    return []
  }
  return allModels.value.filter(m => m.providerId === selectedProviderId.value)
})

/**
 * 提示词分类选项
 */
const promptCategoryOptions = computed(() => {
  return promptCategories.value.map(c => ({
    label: c,
    value: c
  }))
})

/**
 * 当前分类的提示词列表
 */
const currentPrompts = computed(() => {
  if (!selectedPromptCategory.value) {
    const prompts = allPrompts.value.filter(p => p.id === formData.value.systemPromptTemplateId)[0] || null
    if (prompts) {
      selectedPromptCategory.value = prompts.category
    } else {
      selectedPromptCategory.value = allPrompts.value[0]?.category || ''
    }
    return []
  }

  return allPrompts.value.filter(p => p.category === selectedPromptCategory.value)
})

/**
 * 表单验证规则
 */
const rules = computed(() => ({
  modelConfigId: [
    { required: true, message: '请选择模型配置', trigger: 'blur' }
  ],
  systemPromptTemplateId: [
    { required: formData.value.followTemplate, message: '请选择系统提示词模板', trigger: 'change' }
  ]
}))

/**
 * 加载模型提供商
 */
async function loadModelProviders() {
  try {
    loading.value = true
    const response = await modelApi.providerPage({ page: 1, size: 100, enabled: true })
    modelProviders.value = response.data.data.records || []
  } finally {
    loading.value = false
  }
}

/**
 * 加载所有模型
 */
async function loadAllModels() {
  const response = await modelApi.configPage({ page: 1, size: 1000, enabled: true })
  allModels.value = response.data.data.records || []
}

/**
 * 加载提示词分类
 */
async function loadPromptCategories() {
  const response = await promptApi.listCategories()
  promptCategories.value = response.data.data || []
}

/**
 * 加载所有提示词
 */
async function loadAllPrompts() {
  const response = await promptApi.page({ page: 1, size: 1000, enabled: true })
  allPrompts.value = response.data.data.records || []
}

/**
 * 处理模型选择
 */
async function handleModelChange(modelId: string) {
  formData.value.modelConfigId = modelId

  if (showModelParamsOverride.value) {
    await loadModelParams(modelId)
  }
}

/**
 * 加载模型参数
 */
async function loadModelParams(modelId: string) {
  const response = await modelApi.configDetail(modelId)
  const model = response.data.data

  const ec = model.extendConfig as ExtendConfigData | null | undefined
  const extendConfig = ec && typeof ec === 'object'
    ? cleanReasoningExtensions(ec)
    : null

  modelParamsForm.value = {
    streaming: model.streaming,
    temperature: model.temperature,
    topP: model.topP,
    topK: model.topK,
    contextWindow: model.contextWindow,
    maxTokens: model.maxTokens,
    repeatPenalty: model.repeatPenalty,
    seed: model.seed,
    extendConfig
  }

  formData.value.modelParamsOverride = { ...modelParamsForm.value, ...reasoningOnly(formData.value.modelParamsOverride) }
}

/**
 * 处理覆盖模型参数开关
 */
function handleOverrideToggle(checked: boolean) {
  showModelParamsOverride.value = checked
  if (checked && formData.value.modelConfigId) {
    loadModelParams(formData.value.modelConfigId)
  } else {
    formData.value.modelParamsOverride = reasoningOnly(formData.value.modelParamsOverride)
    modelParamsForm.value = {}
  }
}

/**
 * 处理提示词模板选择
 */
let templateRequestVersion = 0

async function handlePromptChange(promptId?: string) {
  formData.value.systemPromptTemplateId = promptId || ''
  if (!promptId) {
    // 解除模板关联时保留已导入或编辑的正文，并取消尚未完成的同步。
    handleFollowTemplateToggle(false)
    return
  }
  const selected = allPrompts.value.find(item => String(item.id) === String(promptId))
  if (selected) {
    formData.value.systemPrompt = selected.content
  }
  await syncTemplateContent(promptId, formData.value.followTemplate)
}

async function syncTemplateContent(promptId: string, requireFollowing = false) {
  const requestVersion = ++templateRequestVersion
  const promptBeforeRequest = formData.value.systemPrompt
  try {
    const response = await promptApi.detail(promptId)
    if (requestVersion === templateRequestVersion
      && String(formData.value.systemPromptTemplateId) === String(promptId)
      && (!requireFollowing || formData.value.followTemplate)
      && formData.value.systemPrompt === promptBeforeRequest) {
      formData.value.systemPrompt = response.data.data.content
    }
  } catch (error) {
    console.warn('同步模板内容失败', error)
  }
}

/**
 * 处理随模板变化开关
 */
function handleFollowTemplateToggle(checked: boolean) {
  formData.value.followTemplate = checked
  if (checked && formData.value.systemPromptTemplateId) {
    void syncTemplateContent(formData.value.systemPromptTemplateId, true)
  } else {
    templateRequestVersion++
  }
}

/* ===================== 供编排页卡片头渲染「模板选择器 + 跟随开关」 ===================== */

/** 卡片头没有分类筛选器，直接提供全部已启用模板。 */
const headerTemplateOptions = computed(() =>
  allPrompts.value.map(item => ({ label: item.name, value: String(item.id) }))
)

/**
 * 是否显示固定系统消息配置（仅 OpenAI 供应商）
 */
const showFixedSystemMessage = computed(() => {
  if (!formData.value.modelConfigId) return false
  const model = allModels.value.find(m => String(m.id) === String(formData.value.modelConfigId))
  if (!model) return false
  const provider = modelProviders.value.find(p => String(p.id) === String(model.providerId))
  return provider?.type === 'OPEN_AI' && model.apiType !== 'RESPONSES'
})
const selectedModel = computed(() => allModels.value.find(m => String(m.id) === String(formData.value.modelConfigId)))
const isResponses = computed(() => selectedModel.value?.apiType === 'RESPONSES')
const supportsSampling = computed(() => !isResponses.value || selectedModel.value?.responsesSampling === true)

/**
 * 扩展配置（用于 v-model 绑定）
 */
const extendConfigForm = computed({
  get: () => (modelParamsForm.value.extendConfig as ExtendConfigData) || null,
  set: (v: ExtendConfigData | null) => {
    modelParamsForm.value = { ...modelParamsForm.value, extendConfig: v }
  }
})

/** 适配参数覆盖表单的宽泛对象类型，保证上下文窗口始终以数字形式回填。 */
const contextWindowOverride = computed<number>({
  get: () => Number(modelParamsForm.value.contextWindow) || 0,
  set: (value) => {
    modelParamsForm.value = { ...modelParamsForm.value, contextWindow: value }
  }
})

/** 适配最大输出 Token 的宽泛参数类型，保证示例值可直接写回覆盖参数。 */
const maxTokensOverride = computed<number>({
  get: () => Number(modelParamsForm.value.maxTokens) || 0,
  set: (value) => {
    modelParamsForm.value = { ...modelParamsForm.value, maxTokens: value }
  }
})

/**
 * 更新模型参数
 */
watch(modelParamsForm, (newVal) => {
  if (showModelParamsOverride.value) {
    formData.value.modelParamsOverride = { ...newVal, ...reasoningOnly(formData.value.modelParamsOverride) }
  }
}, { deep: true })

/**
 * 验证表单
 */
async function validate(): Promise<boolean> {
  if (!props.promptOnly && await reasoningFormRef.value?.validate?.() === false) return false
  if (formData.value.followTemplate && !formData.value.systemPromptTemplateId) {
    return false
  }
  try {
    await formRef.value?.validate()
    return true
  } catch {
    return false
  }
}

onMounted(async () => {
  await Promise.all([
    loadModelProviders(),
    loadAllModels(),
    loadPromptCategories(),
    loadAllPrompts()
  ])

  if (formData.value.modelConfigId) {
    const model = allModels.value.find(m => m.id === formData.value.modelConfigId)
    if (model) {
      selectedProviderId.value = model.providerId
    }
  }

  if (formData.value.systemPromptTemplateId) {
    const prompt = allPrompts.value.find(p => String(p.id) === String(formData.value.systemPromptTemplateId))
    if (prompt) {
      selectedPromptCategory.value = prompt.category
    }
  }

  if (!props.promptOnly && formData.value.modelParamsOverride && Object.keys(formData.value.modelParamsOverride).length > 0) {
    showModelParamsOverride.value = hasSamplingOverride(formData.value.modelParamsOverride)
    const override = formData.value.modelParamsOverride
    const ec = override.extendConfig as ExtendConfigData | null | undefined
    modelParamsForm.value = {
      ...override,
      extendConfig: ec && typeof ec === 'object'
        ? { headers: ec.headers || {}, queryParams: ec.queryParams || {}, bodyParams: ec.bodyParams || {}, fixedSystemMessage: ec.fixedSystemMessage ?? false }
        : null
    }
    delete modelParamsForm.value.reasoning
  }

  // 候选列表就绪后才能把 id 还原为名称，通知编排页刷新概览
  emit('ready')
})

/**
 * 编排概览：当前选中的模型配置与系统提示词模板名称
 * （供智能体编排页渲染「已选 pill」）
 */
function getSummary(): StudioSummary {
  const model = allModels.value.find(m => String(m.id) === String(formData.value.modelConfigId))
  const prompt = allPrompts.value.find(p => String(p.id) === String(formData.value.systemPromptTemplateId))
  return {
    model: { label: '模型', names: model ? [model.name] : [] },
    promptTemplate: { label: '提示词模板', names: prompt ? [prompt.name] : [] }
  }
}

/**
 * 跳转到提示词模板页维护动态参数字段
 */
function goPromptSchema() {
  window.open(router.resolve({ name: RouteNames.PROMPT }).href, '_blank')
}

defineExpose({
  validate,
  getSummary,
  setFollowTemplate: handleFollowTemplateToggle,
  /** 卡片头「跟随模板更新」开关用：模板下拉选项 */
  templateOptions: headerTemplateOptions,
  /** 卡片头用：选择模板 */
  selectTemplate: handlePromptChange
})
</script>

<template>
  <AppSpin :spinning="loading">
    <AForm ref="formRef" :model="formData" :rules="rules" layout="vertical">
      <AFormItem v-if="!promptOnly" label="模型配置" name="modelConfigId" required>
        <template v-if="providerOptions?.length > 0">
          <div class="mb-md">
            <ASegmented
              v-model:value="selectedProviderId"
              :options="providerOptions"
              style="margin-bottom: 12px; background-color: var(--color-bg)"
            />
          </div>
          <ARadioGroup v-model:value="formData.modelConfigId" style="width: 100%">
            <div class="model-grid" v-if="currentModels?.length > 0">
              <ARadio
                v-for="model in currentModels"
                :key="model.id"
                :value="model.id"
                class="model-radio"
                @change="handleModelChange(model.id as string)"
              >
                <div class="model-info">
                  <div class="model-name">{{ model.name }}</div>
                  <div class="model-desc text-placeholder text-xs">{{ model.description }}</div>
                </div>
              </ARadio>
            </div>
            <div v-else class="text-placeholder mt-xs">
              <AButton type="text">未配置模型？</AButton>
              <AButton type="link" :href="router.resolve({ name: RouteNames.MODEL }).href" target="_blank">去配置</AButton>
              <AButton type="link" @click="loadModelProviders();loadAllModels()">刷新</AButton>
            </div>
          </ARadioGroup>
        </template>
        <template v-else>
          <div class="text-placeholder mt-xs">
            <AButton type="text">未添加模型提供商？</AButton>
            <AButton type="link" :href="router.resolve({ name: RouteNames.MODEL }).href" target="_blank">去添加</AButton>
            <AButton type="link" @click="loadModelProviders();loadAllModels()">刷新</AButton>
          </div>
        </template>
      </AFormItem>

      <AgentReasoningSettings v-if="!promptOnly && formData.modelConfigId" ref="reasoningFormRef"
        v-model="formData.modelParamsOverride" :model="selectedReasoningModel" />

      <AFormItem v-if="!promptOnly && formData.modelConfigId" label="覆盖模型参数">
        <ASwitch
          :checked="showModelParamsOverride"
          @change="handleOverrideToggle"
        />
      </AFormItem>

      <div v-if="!promptOnly && formData.modelConfigId && showModelParamsOverride" class="params-override-section">
        <ARow :gutter="16" :key="showModelParamsOverride">
          <ACol v-if="supportsSampling" :span="6">
            <AFormItem label="温度">
              <AInputNumber
                v-model:value="modelParamsForm.temperature"
                :min="0"
                :max="2"
                :step="0.1"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol v-if="supportsSampling" :span="6">
            <AFormItem label="Top P">
              <AInputNumber
                v-model:value="modelParamsForm.topP"
                :min="0"
                :max="1"
                :step="0.1"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol v-if="!isResponses" :span="6">
            <AFormItem label="Top K">
              <AInputNumber
                v-model:value="modelParamsForm.topK"
                :min="0"
                :max="100"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol :span="6">
            <AFormItem>
              <template #label>
                <span>上下文窗口</span>
                <ContextWindowHint v-model="contextWindowOverride" />
              </template>
              <AInputNumber
                v-model:value="modelParamsForm.contextWindow"
                :min="1"
                :max="1000000"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol :span="6">
            <AFormItem>
              <template #label>
                <span>最大输出 Token 数</span>
                <MaxTokensHint v-model="maxTokensOverride" />
              </template>
              <AInputNumber
                v-model:value="maxTokensOverride"
                :min="1"
                :max="1000000"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol v-if="!isResponses" :span="6">
            <AFormItem label="重复惩罚">
              <AInputNumber
                v-model:value="modelParamsForm.repeatPenalty"
                :min="0"
                :max="2"
                :step="0.1"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol v-if="!isResponses" :span="6">
            <AFormItem label="随机种子">
              <AInputNumber
                v-model:value="modelParamsForm.seed"
                style="width: 100%"
                placeholder="请输入随机种子，留空表示随机" />
            </AFormItem>
          </ACol>
          <ACol :span="6">
            <AFormItem label="流式输出">
              <ASwitch v-model:checked="modelParamsForm.streaming" />
            </AFormItem>
          </ACol>
        </ARow>
        <div class="extend-config-wrapper">
          <AFormItem label="扩展配置">
            <ExtendConfigEditor
              v-model="extendConfigForm"
              compact
              :show-fixed-system-message="showFixedSystemMessage"
            />
          </AFormItem>
        </div>
      </div>

      <!-- 提示词模板的选择器搬到卡片头（promptOnly 形态），与「跟随模板更新」开关同行 -->
      <AFormItem
        v-if="!promptOnly"
        label="系统提示词模板"
        name="systemPromptTemplateId"
        :required="formData.followTemplate"
      >
        <template v-if="promptCategoryOptions?.length > 0">
          <div class="mb-md">
            <ASegmented
              v-model:value="selectedPromptCategory"
              :options="promptCategoryOptions"
              style="margin-bottom: 12px; background-color: var(--color-bg)"
            />
          </div>
          <ARadioGroup v-model:value="formData.systemPromptTemplateId" style="width: 100%">
            <div class="prompt-grid" v-if="currentPrompts?.length > 0">
              <ARadio
                v-for="prompt in currentPrompts"
                :key="prompt.id"
                :value="prompt.id"
                class="prompt-radio"
                @change="handlePromptChange(prompt.id as string)"
              >
                <div class="prompt-info">
                  <div class="prompt-name">{{ prompt.name }}</div>
                  <div class="prompt-desc text-placeholder text-xs">{{ prompt.description }}</div>
                </div>
              </ARadio>
            </div>
            <div v-else class="text-placeholder mt-xs">
              <AButton type="text">没有有效的提示模板？</AButton>
              <AButton type="link" :href="router.resolve({ name: RouteNames.PROMPT }).href" target="_blank">去设置</AButton>
              <AButton type="link" @click="loadPromptCategories();loadAllPrompts()">刷新</AButton>
            </div>
          </ARadioGroup>
        </template>
        <template v-else>
          <div class="text-placeholder mt-xs">
            <AButton type="text">未添系统提示词模板？</AButton>
            <AButton type="link" :href="router.resolve({ name: RouteNames.PROMPT }).href" target="_blank">去添加</AButton>
            <AButton type="link" @click="loadPromptCategories();loadAllPrompts()">刷新</AButton>
          </div>
        </template>
      </AFormItem>

      <!-- 编排页：系统提示词 / 动态参数都是块级标签 + 满宽编辑器（与参照页一致） -->
      <template v-if="promptOnly">
        <div class="prompt-block">
          <span class="prompt-block__label">系统提示词</span>
          <SmartCodeEditor
            v-model="formData.systemPrompt"
            :readonly="formData.followTemplate"
            language="markdown"
            height="360px"
          />
          <div v-if="formData.followTemplate" class="text-placeholder text-xs mt-xs">
            已开启「跟随模板更新」，提示词内容随模板变化自动刷新；关闭开关后即可手动编辑。
          </div>
          <div v-else class="text-placeholder text-xs mt-xs">
            选择模板可导入内容后继续编辑，不会随模板更新；清除模板选择会保留当前内容。
          </div>
        </div>

        <div class="prompt-block">
          <span class="prompt-block__label">动态参数</span>
          <div class="prompt-block__params">
            <span class="text-placeholder text-xs">
              提示词中通过 <code v-pre>{{ 变量名 }}</code> 引用动态参数；本智能体的启动参数表单由系统提示词模板定义。
            </span>
            <a-button type="dashed" block size="small" @click="goPromptSchema">
              去提示词模板中维护参数字段
            </a-button>
          </div>
        </div>
      </template>

      <template v-else>
        <AFormItem label="随模板变化" v-if="formData.systemPromptTemplateId">
          <ASwitch
            v-model:checked="formData.followTemplate"
            @change="handleFollowTemplateToggle"
          />
          <div class="text-placeholder text-xs mt-xs">
            开启后,提示词内容将随模板更新而自动更新
          </div>
        </AFormItem>

        <AFormItem v-if="!formData.followTemplate" label="系统提示词">
          <SmartCodeEditor
            v-model="formData.systemPrompt"
            v-if="!formData.followTemplate"
            language="markdown"
            height="350px"
          />
        </AFormItem>
      </template>
    </AForm>
  </AppSpin>
</template>

<style scoped lang="scss">
/* 编排页的块级提示词区（参照页：字段名在编辑器上方，编辑器满宽） */
.prompt-block {
  margin-bottom: 18px;
}

.prompt-block__label {
  display: block;
  margin-bottom: 8px;
  color: #313a4c;
  font-size: 13px;
  font-weight: 600;
}

.prompt-block__params {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px dashed #dfe6f0;
  border-radius: 10px;
  background: #fbfdff;
}

.prompt-block__params code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef4ff;
  color: #1677ff;
  font-size: 11px;
}

.model-grid,
.prompt-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--spacing-sm);
}

.model-radio,
.prompt-radio {
  padding: var(--spacing-sm);
  border: 1px solid var(--color-border-base);
  border-radius: var(--border-radius-md);
  margin: 0 !important;
  width: 100%;
  transition: all var(--transition-base);

  &:hover {
    border-color: var(--color-primary);
    background-color: var(--color-bg-light);
  }
}

.model-info,
.prompt-info {
  .model-name,
  .prompt-name {
    font-weight: 500;
    margin-bottom: 4px;
  }

  .model-desc,
  .prompt-desc {
    line-height: 1.4;
  }
}

.params-override-section {
  padding: var(--spacing-md);
  background-color: #fcfcfc;
  border: 1px solid #eaeaea;
  border-radius: var(--border-radius-md);
  margin-bottom: var(--spacing-md);

  .extend-config-wrapper {
    margin-top: var(--spacing-md);
    padding-top: var(--spacing-md);
    border-top: 1px solid var(--color-border-light);
  }
}
</style>
