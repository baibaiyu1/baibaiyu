<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message, type FormInstance } from 'ant-design-vue'
import { HealthStatus, KbType, RAGMode, type KnowledgeBaseConfig, type KnowledgeBaseConfigVO } from '@/types'
import { LIGHTRAG_DEFAULTS, LIGHTRAG_MODES } from '@/constants/lightrag'
import * as knowledgeApi from '@/api/knowledge'
import KnowledgeModelSelect from './KnowledgeModelSelect.vue'
import { useKnowledgeModelOptions } from '@/composables/useKnowledgeModelOptions'

const props = defineProps<{ data?: KnowledgeBaseConfigVO }>()
const emit = defineEmits<{ success: []; cancel: [] }>()
const formRef = ref<FormInstance>()
const saving = ref(false)
const syncing = ref(false)
const { models, loading: modelsLoading } = useKnowledgeModelOptions()
const modelFields = [
  { key: 'llmModelId', label: '默认文本模型', usage: 'CHAT', placeholder: '使用 LightRAG 服务原配置' },
  { key: 'extractModelId', label: '实体、关系抽取与摘要模型', usage: 'CHAT', placeholder: '使用默认文本模型或服务原配置' },
  { key: 'keywordModelId', label: '检索关键词模型', usage: 'CHAT', placeholder: '使用默认文本模型或服务原配置' },
  { key: 'queryModelId', label: '检索测试回答模型', usage: 'CHAT', placeholder: '使用默认文本模型或服务原配置' },
  { key: 'vlmModelId', label: '图片理解模型', usage: 'IMAGE', placeholder: '使用 LightRAG 服务原配置' },
  { key: 'embeddingModelId', label: '向量模型', usage: 'EMBEDDING', placeholder: '使用 LightRAG 服务原配置' },
  { key: 'rerankModelId', label: '重排模型', usage: 'RERANK', placeholder: '使用 LightRAG 服务原配置' }
] as const
const activeSection = ref('connection')
const isEdit = computed(() => !!props.data?.id)
const form = reactive({
  name: '', description: '',
  connection: { baseUrl: '', apiKey: '', llmModelId: undefined as string | undefined,
    extractModelId: undefined as string | undefined, keywordModelId: undefined as string | undefined,
    queryModelId: undefined as string | undefined, vlmModelId: undefined as string | undefined,
    embeddingModelId: undefined as string | undefined, rerankModelId: undefined as string | undefined },
  retrieval: { ...LIGHTRAG_DEFAULTS },
  http: { timeoutSeconds: 180, maxRetries: 0 }
})

watch(() => props.data, (data) => {
  if (!data) return
  form.name = data.name
  form.description = data.description || ''
  Object.assign(form.connection, data.connectionConfig)
  Object.assign(form.retrieval, data.retrievalConfig)
  Object.assign(form.http, data.httpConfig)
}, { immediate: true })

async function validateUrl(_rule: unknown, value: string) {
  try {
    const url = new URL(value.trim())
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) {
      throw new Error()
    }
  } catch {
    throw new Error('请输入有效的 HTTP(S) 服务地址，不含账号、查询参数或锚点')
  }
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch {
    message.warning('请检查名称、服务地址和检索参数')
    return
  }
  saving.value = true
  try {
    const data: KnowledgeBaseConfig = {
      ...(isEdit.value ? { id: String(props.data!.id) } : {}),
      name: form.name.trim(), description: form.description.trim(), kbType: KbType.LIGHTRAG,
      ragMode: props.data?.ragMode || RAGMode.AGENTIC,
      connectionConfig: { ...form.connection, baseUrl: form.connection.baseUrl.trim().replace(/\/+$/, ''), apiKey: form.connection.apiKey.trim() },
      retrievalConfig: { ...form.retrieval }, httpConfig: { ...form.http },
      endpointConfig: null, rerankingConfig: null, queryRewriteConfig: null, metadataFilters: null,
      healthStatus: props.data?.healthStatus || HealthStatus.UNKNOWN,
      lastSyncTime: props.data?.lastSyncTime || ''
    }
    if (isEdit.value) await knowledgeApi.update(data)
    else await knowledgeApi.save(data)
    if (isEdit.value) {
      await syncModels()
    } else message.success('保存成功；选择平台模型后，首次使用前会同步至 LightRAG')
    emit('success')
  } finally {
    saving.value = false
  }
}

async function syncModels() {
  if (!props.data?.id) return
  syncing.value = true
  try {
    const result = (await knowledgeApi.syncLightRagModels(String(props.data.id))).data.data
    if (result?.restart_required) {
      message.warning(result.rebuild_required
        ? '模型配置已同步。请停止 LightRAG，重建已有向量索引，再重启服务。'
        : '模型配置已同步，请重启 LightRAG 服务后使用。', 10)
    } else message.success('模型配置已生效')
  } finally { syncing.value = false }
}
</script>

<template>
  <h3 class="intro-title">{{ form.name || '知识图谱' }}</h3>
  <div class="knowledge-form">
    <AAlert type="info" show-icon message="知识图谱" description="保存后可从知识库卡片进入管理，上传文档、查看解析进度和浏览知识图谱，也可检索测试并关联智能体。" />
    <AForm ref="formRef" :model="form" layout="vertical">
      <AFormItem v-if="isEdit" label="关联智能体">{{ props.data?.used?.join('、') || '无' }}</AFormItem>
      <AFormItem label="名称" name="name" :rules="[{ required: true, whitespace: true, message: '请输入名称' }, { max: 100, message: '名称最多100个字符' }]">
        <AInput v-model:value="form.name" placeholder="例如：企业制度知识库" />
      </AFormItem>
      <AFormItem label="描述" name="description" :rules="[{ max: 200, message: '描述最多200个字符' }]">
        <ATextarea v-model:value="form.description" :rows="3" placeholder="说明知识库内容与适用范围" />
      </AFormItem>
      <ASegmented v-model:value="activeSection" :options="[{ label: '连接配置', value: 'connection' }, { label: '模型配置', value: 'models' }, { label: '检索配置', value: 'retrieval' }, { label: 'HTTP 配置', value: 'http' }]" block />
      <div v-show="activeSection === 'connection'" class="config-content">
        <AFormItem label="服务地址（Base URL）" :name="['connection', 'baseUrl']" :rules="[{ required: true, message: '请输入服务地址' }, { validator: validateUrl, trigger: 'blur' }]" extra="填写对应知识库的服务地址；不同工作区应连接各自的服务实例。">
          <AInput v-model:value="form.connection.baseUrl" placeholder="例如：http://knowledge-service:9621" />
        </AFormItem>
        <AFormItem label="API 密钥（X-API-Key）" :name="['connection', 'apiKey']" extra="填写知识图谱服务的 API 密钥；服务未启用密钥认证时可留空。">
          <AInputPassword v-model:value="form.connection.apiKey" placeholder="请输入 API Key" autocomplete="new-password" />
        </AFormItem>
      </div>
      <div v-show="activeSection === 'models'" class="config-content">
        <AAlert type="info" show-icon message="选择平台已启用的模型，统一使用模型管理中的地址与认证。" description="保存后同步配置，重启 LightRAG 生效。向量模型必须与已有索引一致；更换时先停止服务并重建索引。LightRAG 需配置 API 密钥；一个服务工作区绑定一个知识库。" />
        <AFormItem v-for="field in modelFields" :key="field.key" :label="field.label">
          <KnowledgeModelSelect v-model:value="form.connection[field.key]" :models="models" :loading="modelsLoading"
            :usage="field.usage" :placeholder="field.placeholder" />
        </AFormItem>
        <AButton v-if="isEdit" :loading="syncing" @click="syncModels">同步已保存的模型配置</AButton>
      </div>
      <div v-show="activeSection === 'retrieval'" class="config-content">
        <AFormItem label="检索模式" :name="['retrieval', 'mode']" :rules="[{ required: true }]">
          <ASelect v-model:value="form.retrieval.mode" :options="LIGHTRAG_MODES" />
        </AFormItem>
        <AFormItem label="返回片段数（Top K）" :name="['retrieval', 'topK']" :rules="[{ required: true, type: 'integer', min: 1, max: 1000 }]" extra="有图谱结果时会额外返回一条实体与关系上下文。">
          <AInputNumber v-model:value="form.retrieval.topK" :min="1" :max="1000" :precision="0" />
        </AFormItem>
        <AFormItem label="图谱检索数量" :name="['retrieval', 'graphTopK']" :rules="[{ required: true, type: 'integer', min: 1, max: 1000 }]">
          <AInputNumber v-model:value="form.retrieval.graphTopK" :min="1" :max="1000" :precision="0" />
        </AFormItem>
        <AFormItem v-for="item in [{ key: 'maxEntityTokens', label: '实体 Token 上限' }, { key: 'maxRelationTokens', label: '关系 Token 上限' }, { key: 'maxTotalTokens', label: '总上下文 Token 上限' }] as const" :key="item.key" :label="item.label" :name="['retrieval', item.key]" :rules="[{ required: true, type: 'integer', min: 1, max: 1000000 }]">
          <AInputNumber v-model:value="form.retrieval[item.key]" :min="1" :max="1000000" :precision="0" />
        </AFormItem>
        <AFormItem label="启用重排" extra="在模型配置中选择平台重排模型，或使用 LightRAG 服务已有的重排模型。">
          <ASwitch v-model:checked="form.retrieval.enableRerank" />
        </AFormItem>
        <p class="text-secondary">知识图谱使用服务端检索策略筛选结果，不提供统一相关度分数。</p>
      </div>
      <div v-show="activeSection === 'http'" class="config-content">
        <AFormItem label="单次请求超时（秒）" :name="['http', 'timeoutSeconds']" :rules="[{ required: true, type: 'integer', min: 1, max: 600 }]">
          <AInputNumber v-model:value="form.http.timeoutSeconds" :min="1" :max="600" :precision="0" />
        </AFormItem>
        <AFormItem label="最大重试次数" :name="['http', 'maxRetries']" :rules="[{ required: true, type: 'integer', min: 0, max: 3 }]" extra="仅连接失败、超时、限流及服务端错误可重试；重试可能增加检索模型用量。">
          <AInputNumber v-model:value="form.http.maxRetries" :min="0" :max="3" :precision="0" />
        </AFormItem>
      </div>
    </AForm>
  </div>
  <div class="form-footer">
    <AButton v-if="!isEdit" @click="emit('cancel')">取消</AButton>
    <AButton type="primary" :loading="saving" @click="save">保存</AButton>
  </div>
</template>

<style scoped lang="scss">
.intro-title { font-size: var(--font-size-2xl); font-weight: 600; margin-bottom: var(--spacing-sm); }
.knowledge-form { max-height: calc(100vh - 180px); overflow: auto; }
.ant-alert { margin-bottom: 16px; }
.config-content { padding: 16px 0 4px; }
.ant-input-number { width: 100%; }
.form-footer { display: flex; justify-content: center; gap: var(--spacing-sm); margin-top: var(--spacing-base); }
</style>
