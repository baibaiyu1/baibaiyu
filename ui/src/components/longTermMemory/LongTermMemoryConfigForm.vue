/**
 * 长期记忆配置表单组件
 * 根据记忆类型动态展示对应的专属配置项
 *
 */
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { InfoCircleOutlined } from '@ant-design/icons-vue'
import type { LongTermMemoryConfig, LongTermMemoryConfigVO } from '@/types'
import * as longTermMemoryApi from '@/api/longTermMemory'

/**
 * Props定义
 */
const props = defineProps<{
  visible: boolean
  data?: LongTermMemoryConfigVO
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: []
}>()

/**
 * 表单引用
 */
const formRef = ref()

/**
 * 记忆类型选项
 */
const memoryTypeOptions = [
  { label: 'Mem0 长期记忆', value: 'MEM0' },
  { label: 'ReMe 长期记忆（阿里通义千问）', value: 'REME' },
  { label: '百炼记忆库（阿里云百炼）', value: 'BAILIAN' }
]

/**
 * memoryMode 选项
 */
const memoryModeOptions = [
  { label: 'Agent 自主控制', value: 'AGENT_CONTROL' },
  { label: '系统自动管理', value: 'STATIC_CONTROL' },
  { label: '两者结合', value: 'BOTH' }
]

/**
 * 表单数据
 */
const formData = ref({
  configName: '',
  memoryType: 'MEM0' as 'MEM0' | 'REME' | 'BAILIAN',
  memoryMode: 'BOTH' as string,
  // Mem0 专属
  mem0ApiBaseUrl: 'https://api.mem0.ai',
  mem0ApiKey: '',
  mem0ApiType: 'platform' as 'platform' | 'self-hosted',
  // ReMe 专属
  remeApiBaseUrl: 'https://api.reme.ai',
  remeTimeout: 30 as number,
  // 百炼 专属
  bailianApiKey: '',
  bailianMemoryLibraryId: '',
  bailianProjectId: '',
  bailianTopK: 5 as number,
  bailianMinScore: 0.5 as number
})

/**
 * 提交中状态
 */
const submitting = ref(false)

/**
 * 是否为编辑模式
 */
const isEdit = computed(() => !!props.data?.id)

/**
 * 记忆类型对应的默认配置
 */
const defaultConfigs = {
  MEM0: () => ({
    memoryMode: 'BOTH',
    apiBaseUrl: 'https://api.mem0.ai',
    apiKey: '',
    apiType: 'platform'
  }),
  REME: () => ({
    memoryMode: 'BOTH',
    apiBaseUrl: 'https://api.reme.ai',
    timeout: 30
  }),
  BAILIAN: () => ({
    memoryMode: 'BOTH',
    apiKey: '',
    memoryLibraryId: '',
    projectId: '',
    topK: 5,
    minScore: 0.5
  })
}

/**
 * 表单验证规则
 */
const rules = {
  configName: [
    { required: true, message: '请输入配置名称', trigger: 'blur' }
  ]
}

/**
 * 重置表单
 */
function resetForm() {
  formData.value = {
    configName: '',
    memoryType: 'MEM0',
    memoryMode: 'BOTH',
    mem0ApiBaseUrl: 'https://api.mem0.ai',
    mem0ApiKey: '',
    mem0ApiType: 'platform',
    remeApiBaseUrl: 'https://api.reme.ai',
    remeTimeout: 30,
    bailianApiKey: '',
    bailianMemoryLibraryId: '',
    bailianProjectId: '',
    bailianTopK: 5,
    bailianMinScore: 0.5
  }
  formRef.value?.resetFields()
}

/**
 * 从 config JSON 解析表单数据
 */
function loadConfigToForm(config: Record<string, unknown> | null) {
  if (!config) {
    resetForm()
    return
  }
  formData.value.memoryMode = (config.memoryMode as string) || 'BOTH'

  switch (formData.value.memoryType) {
    case 'MEM0':
      formData.value.mem0ApiBaseUrl = (config.apiBaseUrl as string) || 'https://api.mem0.ai'
      formData.value.mem0ApiKey = (config.apiKey as string) || ''
      formData.value.mem0ApiType = (config.apiType as 'platform' | 'self-hosted') || 'platform'
      break
    case 'REME':
      formData.value.remeApiBaseUrl = (config.apiBaseUrl as string) || 'https://api.reme.ai'
      formData.value.remeTimeout = (config.timeout as number) || 30
      break
    case 'BAILIAN':
      formData.value.bailianApiKey = (config.apiKey as string) || ''
      formData.value.bailianMemoryLibraryId = (config.memoryLibraryId as string) || ''
      formData.value.bailianProjectId = (config.projectId as string) || ''
      formData.value.bailianTopK = (config.topK as number) || 5
      formData.value.bailianMinScore = (config.minScore as number) || 0.5
      break
  }
}

/**
 * 监听弹窗显示状态
 */
watch(() => props.visible, (val) => {
  if (val) {
    if (props.data) {
      formData.value.configName = props.data.configName
      formData.value.memoryType = props.data.memoryType
      loadConfigToForm(props.data.config)
    } else {
      resetForm()
    }
  }
})

/**
 * 监听记忆类型切换，加载默认配置
 */
watch(() => formData.value.memoryType, (newType) => {
  const defaultCfg = defaultConfigs[newType]()
  loadConfigToForm(defaultCfg)
})

/**
 * 从当前表单数据构建 config JSON
 */
function buildConfig(): Record<string, unknown> {
  const base: Record<string, unknown> = {
    memoryMode: formData.value.memoryMode
  }

  switch (formData.value.memoryType) {
    case 'MEM0':
      base.apiBaseUrl = formData.value.mem0ApiBaseUrl
      base.apiKey = formData.value.mem0ApiKey
      base.apiType = formData.value.mem0ApiType
      break
    case 'REME':
      base.apiBaseUrl = formData.value.remeApiBaseUrl
      base.timeout = formData.value.remeTimeout
      break
    case 'BAILIAN':
      base.apiKey = formData.value.bailianApiKey
      base.memoryLibraryId = formData.value.bailianMemoryLibraryId
      base.projectId = formData.value.bailianProjectId
      base.topK = formData.value.bailianTopK
      base.minScore = formData.value.bailianMinScore
      break
  }

  return base
}

/**
 * 处理提交
 */
async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const entity: LongTermMemoryConfig = {
      configName: formData.value.configName,
      memoryType: formData.value.memoryType,
      config: buildConfig()
    }

    if (isEdit.value && props.data) {
      entity.id = props.data.id
      await longTermMemoryApi.update(entity)
      message.success('更新成功')
    } else {
      await longTermMemoryApi.save(entity)
      message.success('创建成功')
    }

    emit('update:visible', false)
    emit('success')
  } catch (error) {
    console.error('提交失败:', error)
  } finally {
    submitting.value = false
  }
}

/**
 * 处理取消
 */
function handleCancel() {
  emit('update:visible', false)
}
</script>

<template>
  <AModal
    :open="visible"
    :title="isEdit ? '编辑长期记忆配置' : '新增长期记忆配置'"
    :confirm-loading="submitting"
    :destroy-on-close="true"
    width="720px"
    @ok="handleSubmit"
    @cancel="handleCancel"
  >
    <AForm
      ref="formRef"
      :model="formData"
      :rules="rules"
      layout="vertical"
    >
      <!-- 基本配置 -->
      <ARow :gutter="16">
        <ACol :span="24">
          <AFormItem label="配置名称" name="configName">
            <AInput v-model:value="formData.configName" placeholder="请输入配置名称" />
          </AFormItem>
        </ACol>
      </ARow>

      <!-- 记忆控制模式 -->
      <ARow :gutter="16">
        <ACol :span="12">
          <AFormItem label="记忆类型" name="memoryType">
            <ASelect
              v-model:value="formData.memoryType"
              :options="memoryTypeOptions"
              placeholder="请选择记忆类型"
            />
          </AFormItem>
        </ACol>
        <ACol :span="12">
          <AFormItem>
            <template #label>
              <ATooltip title="AGENT_CONTROL: Agent自主决定何时读写记忆；STATIC_CONTROL: 系统在每轮对话后自动执行；BOTH: 两者结合">
                <span>记忆控制模式</span><InfoCircleOutlined class="text-secondary cursor-pointer" />
              </ATooltip>
            </template>
            <ASelect
              v-model:value="formData.memoryMode"
              :options="memoryModeOptions"
              style="width: 100%"
            />
          </AFormItem>
        </ACol>
      </ARow>

      <ADivider style="margin: 8px 0" />

      <!-- ===== Mem0 专属配置 ===== -->
      <template v-if="formData.memoryType === 'MEM0'">
        <ARow :gutter="16">
          <ACol :span="24">
            <AFormItem label="服务地址">
              <AInput v-model:value="formData.mem0ApiBaseUrl" placeholder="https://api.mem0.ai" />
              <div class="text-placeholder text-xs mt-xs">
                Platform Mem0: https://api.mem0.ai，自建 Mem0: http://localhost:8000
              </div>
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="部署类型">
              <ASelect
                v-model:value="formData.mem0ApiType"
                style="width: 100%"
              >
                <ASelectOption value="platform">Platform Mem0</ASelectOption>
                <ASelectOption value="self-hosted">自建 Mem0</ASelectOption>
              </ASelect>
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="API 密钥">
              <AInputPassword
                v-model:value="formData.mem0ApiKey"
                placeholder="输入 Mem0 API Key"
                autocomplete="new-password"
              />
              <div class="text-placeholder text-xs mt-xs">Platform Mem0 必需，自建 Mem0 可选</div>
            </AFormItem>
          </ACol>
        </ARow>
      </template>

      <!-- ===== ReMe 专属配置 ===== -->
      <template v-if="formData.memoryType === 'REME'">
        <ARow :gutter="16">
          <ACol :span="12">
            <AFormItem label="服务地址">
              <AInput v-model:value="formData.remeApiBaseUrl" placeholder="https://api.reme.ai" />
              <div class="text-placeholder text-xs mt-xs">阿里通义千问 ReMe 记忆服务地址</div>
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="请求超时（秒）">
              <AInputNumber
                v-model:value="formData.remeTimeout"
                :min="5"
                :max="300"
                style="width: 100%"
              />
              <div class="text-placeholder text-xs mt-xs">请求 ReMe 服务的超时时间</div>
            </AFormItem>
          </ACol>
        </ARow>
      </template>

      <!-- ===== 百炼记忆库专属配置 ===== -->
      <template v-if="formData.memoryType === 'BAILIAN'">
        <ARow :gutter="16">
          <ACol :span="12">
            <AFormItem label="API 密钥">
              <AInputPassword
                v-model:value="formData.bailianApiKey"
                placeholder="输入阿里云 API Key"
                autocomplete="new-password"
              />
              <div class="text-placeholder text-xs mt-xs">阿里云百炼平台 API Key</div>
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="记忆库 ID">
              <AInput
                v-model:value="formData.bailianMemoryLibraryId"
                placeholder="输入记忆库 ID"
              />
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="项目 ID">
              <AInput
                v-model:value="formData.bailianProjectId"
                placeholder="输入项目 ID（可选）"
              />
            </AFormItem>
          </ACol>
          <ACol :span="6">
            <AFormItem label="TopK">
              <AInputNumber
                v-model:value="formData.bailianTopK"
                :min="1"
                :max="100"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
          <ACol :span="6">
            <AFormItem label="最低匹配分数">
              <AInputNumber
                v-model:value="formData.bailianMinScore"
                :min="0"
                :max="1"
                :step="0.1"
                style="width: 100%"
              />
            </AFormItem>
          </ACol>
        </ARow>
      </template>
    </AForm>
  </AModal>
</template>

<style scoped lang="scss">
.text-secondary {
  color: var(--color-text-secondary);
}
.cursor-pointer {
  cursor: pointer;
}
</style>
