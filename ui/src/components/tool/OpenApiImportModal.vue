<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * OpenAPI 导入向导
 *
 * 三步：粘贴/上传文档 → 勾选接口并配置公共选项 → 查看导入结果。
 * 导入按工具编号幂等，重复导入同一文档只会更新已有工具的实现与参数。
 *
 * @component
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { InboxOutlined, UploadOutlined } from '@ant-design/icons-vue'
import * as toolApi from '@/api/tool'
import type { OpenApiImportDTO, OpenApiOperationVO, OpenApiPreviewVO } from '@/types'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  success: []
}>()

/** 当前步骤 */
const step = ref(0)
const specText = ref('')
const fileName = ref('')
const parsing = ref(false)
const importing = ref(false)
const preview = ref<OpenApiPreviewVO | null>(null)
const selectedKeys = ref<string[]>([])
const result = ref<{ inserted: number; updated: number; skipped: number } | null>(null)

/** 公共选项 */
const baseUrl = ref('')
const category = ref('')
const authHeaderName = ref('')
const authHeaderValue = ref('')

const operations = computed<OpenApiOperationVO[]>(() => preview.value?.operations || [])
const allSelected = computed(() => operations.value.length > 0 && selectedKeys.value.length === operations.value.length)
const indeterminate = computed(() => selectedKeys.value.length > 0 && !allSelected.value)

/**
 * 操作唯一键（operationId 为空时用「方法 路径」兜底，与后端一致）
 */
function operationKey(item: OpenApiOperationVO) {
  return item.operationId && item.operationId.trim()
    ? item.operationId
    : `${item.method.toLowerCase()} ${item.path}`
}

/** 弹窗打开时重置 */
watch(() => props.open, (visible) => {
  if (visible) {
    step.value = 0
    specText.value = ''
    fileName.value = ''
    preview.value = null
    selectedKeys.value = []
    result.value = null
    baseUrl.value = ''
    category.value = ''
    authHeaderName.value = ''
    authHeaderValue.value = ''
  }
})

/**
 * 读取上传的文档文件
 */
function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    specText.value = String(reader.result || '')
    fileName.value = file.name
    message.success(`已读取 ${file.name}`)
  }
  reader.onerror = () => message.error('文件读取失败')
  reader.readAsText(file)
}

/**
 * 解析文档
 */
async function handleParse() {
  if (!specText.value.trim()) {
    message.warning('请先粘贴或上传 OpenAPI 文档')
    return
  }
  parsing.value = true
  try {
    const response = await toolApi.openApiPreview({
      spec: specText.value,
      baseUrl: baseUrl.value.trim() || undefined
    } as OpenApiImportDTO)
    preview.value = response.data.data
    selectedKeys.value = (preview.value?.operations || []).map(operationKey)
    if (!baseUrl.value && preview.value?.baseUrl) {
      baseUrl.value = preview.value.baseUrl
    }
    step.value = 1
    message.success(`识别到 ${operations.value.length} 个接口`)
  } catch (e) {
    console.error('解析 OpenAPI 文档失败:', e)
  } finally {
    parsing.value = false
  }
}

/** 全选 / 取消全选 */
function toggleAll(checked: boolean) {
  selectedKeys.value = checked ? operations.value.map(operationKey) : []
}

/**
 * 执行导入
 */
async function handleImport() {
  if (!selectedKeys.value.length) {
    message.warning('请至少选择一个接口')
    return
  }
  importing.value = true
  try {
    const response = await toolApi.openApiImport({
      spec: specText.value,
      operationIds: selectedKeys.value,
      baseUrl: baseUrl.value.trim() || undefined,
      category: category.value.trim() || undefined,
      authHeaderName: authHeaderName.value.trim() || undefined,
      authHeaderValue: authHeaderValue.value.trim() || undefined
    } as OpenApiImportDTO)
    result.value = response.data.data
    step.value = 2
    message.success('导入完成')
    emit('success')
  } catch (e) {
    console.error('导入失败:', e)
  } finally {
    importing.value = false
  }
}

function close() {
  emit('update:open', false)
}

/** 方法对应的标签颜色 */
function methodColor(method: string) {
  switch (method.toUpperCase()) {
    case 'GET':
      return 'blue'
    case 'POST':
      return 'green'
    case 'PUT':
      return 'orange'
    case 'DELETE':
      return 'red'
    default:
      return 'default'
  }
}
</script>

<template>
  <a-modal
    :open="open"
    title="OpenAPI 导入"
    width="920px"
    :footer="null"
    :mask-closable="false"
    @cancel="close"
  >
    <a-steps
      :current="step"
      :items="[
        { title: '提供文档', description: '粘贴或上传 OpenAPI / Swagger' },
        { title: '选择接口', description: '勾选需要导入的接口' },
        { title: '导入结果', description: '查看新增与更新数量' }
      ]"
      class="openapi-steps"
    />

    <!-- 步骤一：提供文档 -->
    <div v-if="step === 0" class="openapi-step">
      <div class="openapi-uploader">
        <a-button :loading="parsing">
          <template #icon><UploadOutlined /></template>
          <label class="openapi-file-label">
            选择文件（.json / .yaml / .yml）
            <input type="file" accept=".json,.yaml,.yml,application/json" @change="handleFileChange" />
          </label>
        </a-button>
        <span v-if="fileName" class="openapi-file-name">{{ fileName }}</span>
      </div>
      <a-textarea
        v-model:value="specText"
        :rows="14"
        placeholder="在此粘贴 OpenAPI 3.x / Swagger 2.0 文档内容（支持 JSON 与 YAML）"
      />
      <div class="openapi-tip">
        <InboxOutlined />
        导入后每个接口会生成一个自定义工具（Groovy 动态工具），运行时按参数拼装 URL 发起 HTTP 调用。
      </div>
      <div class="openapi-actions">
        <a-button @click="close">取消</a-button>
        <a-button type="primary" :loading="parsing" @click="handleParse">解析文档</a-button>
      </div>
    </div>

    <!-- 步骤二：选择接口 -->
    <div v-else-if="step === 1" class="openapi-step">
      <a-descriptions size="small" :column="2" bordered class="openapi-doc-info">
        <a-descriptions-item label="文档">{{ preview?.title }}（{{ preview?.specVersion }}）</a-descriptions-item>
        <a-descriptions-item label="版本">{{ preview?.version || '-' }}</a-descriptions-item>
        <a-descriptions-item label="服务地址" :span="2">
          {{ preview?.servers?.length ? preview?.servers?.join('、') : '文档未声明' }}
        </a-descriptions-item>
      </a-descriptions>

      <div class="openapi-options">
        <label>
          <span>基地址</span>
          <a-input v-model:value="baseUrl" placeholder="如 https://api.example.com" />
        </label>
        <label>
          <span>工具分类</span>
          <a-input v-model:value="category" placeholder="留空则用文档 tag" />
        </label>
        <label>
          <span>鉴权头名称</span>
          <a-input v-model:value="authHeaderName" placeholder="如 Authorization" />
        </label>
        <label>
          <span>鉴权头取值</span>
          <a-input v-model:value="authHeaderValue" placeholder="如 Bearer xxx" />
        </label>
      </div>

      <div class="openapi-select-bar">
        <a-checkbox
          :checked="allSelected"
          :indeterminate="indeterminate"
          @change="(e: { target: { checked: boolean } }) => toggleAll(e.target.checked)"
        >
          全选
        </a-checkbox>
        <span>已选 {{ selectedKeys.length }} / {{ operations.length }} 个接口</span>
      </div>

      <div class="openapi-operation-list">
        <a-checkbox-group v-model:value="selectedKeys" class="openapi-operation-group">
          <label v-for="item in operations" :key="operationKey(item)" class="openapi-operation">
            <a-checkbox :value="operationKey(item)" />
            <a-tag :color="methodColor(item.method)" class="openapi-method">{{ item.method }}</a-tag>
            <span class="openapi-path">{{ item.path }}</span>
            <span class="openapi-summary">{{ item.summary || item.operationId || '-' }}</span>
            <span class="openapi-meta">
              {{ item.paramCount }} 参数<template v-if="item.hasBody"> · 含请求体</template>
            </span>
            <a-tag v-if="item.exists" color="orange" class="openapi-exists">更新已有工具</a-tag>
          </label>
        </a-checkbox-group>
      </div>

      <div class="openapi-actions">
        <a-button @click="step = 0">上一步</a-button>
        <a-button type="primary" :loading="importing" @click="handleImport">
          导入选中 {{ selectedKeys.length }} 个接口
        </a-button>
      </div>
    </div>

    <!-- 步骤三：导入结果 -->
    <div v-else class="openapi-step">
      <a-result status="success" title="导入完成">
        <template #subTitle>
          新增 {{ result?.inserted || 0 }} 个 · 更新 {{ result?.updated || 0 }} 个 · 跳过 {{ result?.skipped || 0 }} 个
        </template>
        <template #extra>
          <a-button type="primary" @click="close">完成</a-button>
        </template>
      </a-result>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
.openapi-steps {
  margin-bottom: 18px;
}

.openapi-step {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.openapi-uploader {
  display: flex;
  align-items: center;
  gap: 10px;
}

.openapi-file-label {
  display: inline-block;
  cursor: pointer;

  input {
    display: none;
  }
}

.openapi-file-name {
  color: #8c8c8c;
  font-size: 12px;
}

.openapi-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  color: #64748b;
  border-radius: 6px;
  background: #f6f8fc;
  font-size: 12px;
}

.openapi-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.openapi-doc-info {
  margin-bottom: 4px;
}

.openapi-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;

  label {
    display: flex;
    align-items: center;
    gap: 8px;

    span {
      flex: 0 0 84px;
      color: #4b5563;
      font-size: 13px;
    }
  }
}

.openapi-select-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  color: #6b7280;
  font-size: 13px;
  border-bottom: 1px solid #f0f0f0;
}

.openapi-operation-list {
  max-height: 320px;
  overflow-y: auto;
}

.openapi-operation-group {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.openapi-operation {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  border-bottom: 1px dashed #f5f5f5;
  cursor: pointer;

  &:hover {
    background: #fafcff;
  }
}

.openapi-method {
  min-width: 56px;
  margin: 0;
  text-align: center;
}

.openapi-path {
  color: #1f2937;
  font-family: Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.openapi-summary {
  flex: 1;
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.openapi-meta,
.openapi-exists {
  flex: 0 0 auto;
  color: #9ca3af;
  font-size: 12px;
}
</style>
