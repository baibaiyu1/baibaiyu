/**
 * MCP 工具调试参数表单 - 基于 JSON Schema 动态渲染
 *
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CodeOutlined, FormOutlined } from '@ant-design/icons-vue'

const props = defineProps<{
  schema: Record<string, unknown> | null
}>()

const modelValue = defineModel<Record<string, unknown>>({ default: () => ({}) })

/** 是否使用 JSON 原始编辑模式 */
const rawMode = ref(false)
const rawJson = ref('{}')
const rawJsonError = ref('')

/** 解析 schema 的 properties */
const schemaProperties = computed(() => {
  if (!props.schema) return {}
  const schemaProps = props.schema.properties as Record<string, SchemaField> | undefined
  return schemaProps || {}
})

/** schema 中 required 字段列表 */
const requiredFields = computed(() => {
  if (!props.schema) return []
  return (props.schema.required as string[]) || []
})

/** 字段条目列表（排序：required 在前） */
const fieldEntries = computed(() => {
  const entries = Object.entries(schemaProperties.value)
  return entries.sort(([a], [b]) => {
    const aReq = requiredFields.value.includes(a) ? 0 : 1
    const bReq = requiredFields.value.includes(b) ? 0 : 1
    return aReq - bReq
  })
})

/** 无参数 */
const hasNoParams = computed(() => fieldEntries.value.length === 0)

interface SchemaField {
  type?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  items?: Record<string, unknown>
  properties?: Record<string, unknown>
}

/** 获取字段类型 */
function getFieldType(field: SchemaField): string {
  if (field.enum && field.enum.length > 0) return 'enum'
  if (field.type === 'object' || field.type === 'array') return 'json'
  if (field.type === 'boolean') return 'boolean'
  if (field.type === 'number' || field.type === 'integer') return 'number'
  // 默认 string
  return 'string'
}

/** 获取字段描述 */
function getFieldDescription(field: SchemaField): string {
  return field.description || ''
}

/** 更新字段值 */
function updateField(key: string, value: unknown) {
  modelValue.value = { ...modelValue.value, [key]: value }
}

/** 切换 JSON 原始编辑模式 */
function toggleRawMode() {
  if (!rawMode.value) {
    // 进入 raw 模式：将当前表单数据序列化
    rawJson.value = JSON.stringify(modelValue.value, null, 2)
    rawJsonError.value = ''
  } else {
    // 退出 raw 模式：解析 JSON 并回填
    try {
      const parsed = JSON.parse(rawJson.value)
      if (typeof parsed === 'object' && parsed !== null) {
        modelValue.value = parsed
        rawJsonError.value = ''
      } else {
        rawJsonError.value = 'JSON 必须是对象类型'
        return
      }
    } catch (e) {
      rawJsonError.value = `JSON 格式错误: ${(e as Error).message}`
      return
    }
  }
  rawMode.value = !rawMode.value
}

/** 监听 rawJson 变化实时校验 */
function handleRawJsonInput(value: string) {
  rawJson.value = value
  try {
    JSON.parse(value)
    rawJsonError.value = ''
  } catch (e) {
    rawJsonError.value = `JSON 格式错误: ${(e as Error).message}`
  }
}

/** schema 变化时重置表单 */
watch(() => props.schema, () => {
  modelValue.value = {}
  rawMode.value = false
  rawJson.value = '{}'
}, { immediate: false })

/** 是否为长文本字段（description 中包含多行提示或类型暗示） */
function isTextArea(field: SchemaField): boolean {
  const desc = (field.description || '').toLowerCase()
  return desc.includes('multiline') || desc.includes('textarea') || desc.includes('long text')
}
</script>

<template>
  <div class="mcp-debug-form">
    <!-- 无参数提示 -->
    <div v-if="hasNoParams" class="no-params">
      <div class="no-params-icon">
        <CodeOutlined />
      </div>
      <span class="text-placeholder">此工具无需输入参数</span>
    </div>

    <template v-else>
      <!-- 模式切换 -->
      <div class="form-mode-toggle">
        <span class="form-mode-label text-placeholder text-xs">输入参数</span>
        <AButton type="link" size="small" @click="toggleRawMode">
          <template #icon>
            <CodeOutlined v-if="!rawMode" />
            <FormOutlined v-else />
          </template>
          {{ rawMode ? '表单模式' : 'JSON 编辑' }}
        </AButton>
      </div>

      <!-- JSON 原始编辑模式 -->
      <template v-if="rawMode">
        <ATextarea
          :value="rawJson"
          @update:value="handleRawJsonInput"
          :autoSize="{ minRows: 6, maxRows: 16 }"
          class="raw-json-editor"
          spellcheck="false"
        />
        <div v-if="rawJsonError" class="raw-json-error">
          {{ rawJsonError }}
        </div>
      </template>

      <!-- 表单模式 -->
      <template v-else>
        <div class="form-fields">
          <div
            v-for="[key, field] in fieldEntries"
            :key="key"
            class="form-field"
          >
            <div class="field-label">
              <span class="field-name">{{ key }}</span>
              <span v-if="requiredFields.includes(key)" class="field-required">*</span>
              <span class="field-type text-placeholder text-xs">
                {{ field.type || 'string' }}
              </span>
            </div>
            <div v-if="getFieldDescription(field)" class="field-desc text-placeholder text-xs">
              {{ getFieldDescription(field) }}
            </div>

            <!-- 枚举类型 -->
            <ASelect
              v-if="getFieldType(field) === 'enum'"
              :value="modelValue[key]"
              @update:value="(v: unknown) => updateField(key, v)"
              :placeholder="`选择 ${key}`"
              :options="(field.enum || []).map((v: unknown) => ({ label: String(v), value: v }))"
              style="width: 100%"
            />

            <!-- 布尔类型 -->
            <ASwitch
              v-else-if="getFieldType(field) === 'boolean'"
              style="width: 60px"
              :checked="Boolean(modelValue[key])"
              @update:checked="(v: boolean) => updateField(key, v)"
              checked-children="true"
              un-checked-children="false"
            />

            <!-- 数字类型 -->
            <AInputNumber
              v-else-if="getFieldType(field) === 'number'"
              :value="modelValue[key] as number"
              @update:value="(v: number | null) => updateField(key, v)"
              :placeholder="`输入 ${key}`"
              style="width: 100%"
            />

            <!-- JSON 对象/数组类型 -->
            <ATextarea
              v-else-if="getFieldType(field) === 'json'"
              :value="typeof modelValue[key] === 'string' ? (modelValue[key] as string) : JSON.stringify(modelValue[key] ?? field.default ?? (field.type === 'array' ? [] : {}), null, 2)"
              @update:value="(v: string) => { try { updateField(key, JSON.parse(v)) } catch { updateField(key, v) } }"
              :autoSize="{ minRows: 3, maxRows: 8 }"
              class="raw-json-editor"
              spellcheck="false"
            />

            <!-- 长文本 -->
            <ATextarea
              v-else-if="isTextArea(field)"
              :value="(modelValue[key] as string) ?? ''"
              @update:value="(v: string) => updateField(key, v)"
              :placeholder="`输入 ${key}`"
              :autoSize="{ minRows: 3, maxRows: 8 }"
            />

            <!-- 默认字符串 -->
            <AInput
              v-else
              :value="(modelValue[key] as string) ?? ''"
              @update:value="(v: string) => updateField(key, v)"
              :placeholder="`输入 ${key}`"
            />
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped lang="scss">
.mcp-debug-form {
  padding: 1px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.no-params {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px;
  justify-content: center;

  .no-params-icon {
    font-size: 16px;
    opacity: 0.45;
  }
}

.form-mode-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-mode-label {
  font-weight: 500;
}

.form-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  display: flex;
  align-items: center;
  gap: 4px;
}

.field-name {
  font-size: 13px;
  font-weight: 500;
}

.field-required {
  color: #ff4d4f;
  font-size: 12px;
}

.field-type {
  margin-left: 4px;
}

.field-desc {
  line-height: 1.4;
}

.raw-json-editor {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
}

.raw-json-error {
  color: #ff4d4f;
  font-size: 12px;
  margin-top: -4px;
}
</style>
