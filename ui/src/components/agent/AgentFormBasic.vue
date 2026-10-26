/**
 * 智能体基本信息表单组件
 *
 */
<script setup lang="ts">
import { ref, computed } from 'vue'
import { InfoCircleOutlined, PlusOutlined, CloseOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import * as agentApi from '@/api/agent'

/**
 * Props定义
 */
const props = withDefaults(defineProps<{
  modelValue: {
    name: string
    agentCode: string
    description: string
    tag: string
    tags?: string[]
  }
  tags: string[]
  /** 租户编号，用于作为agentCode前缀 */
  tenantCode?: string
  /** 创建时隐藏编号，交由服务端生成。 */
  showAgentCode?: boolean
}>(), { showAgentCode: true })

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:modelValue': [value: {
    name: string
    agentCode: string
    description: string
    tag: string
    tags?: string[]
  }]
}>()

const formRef = ref()
const newTagName = ref('')
const tagModalOpen = ref(false)
const tagLoading = ref(false)
const tagLoadFailed = ref(false)
const tagAdding = ref(false)
const tagSearch = ref('')
const catalogTags = ref<string[]>([])
const draftTags = ref<string[]>([])

/**
 * 表单数据
 */
const formData = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const selectedTags = computed({
  get: () => formData.value.tags ?? (formData.value.tag ? [formData.value.tag] : []),
  set: (tags: string[]) => {
    formData.value = { ...formData.value, tags, tag: tags[0] || '' }
  }
})

/**
 * agentCode 后缀计算属性
 * 自动剥离/拼接租户编号前缀，用户只需输入后缀部分
 */
const agentCodeSuffix = computed({
  get: () => {
    const code = formData.value.agentCode
    if (props.tenantCode && code?.startsWith(props.tenantCode + '-')) {
      return code.substring(props.tenantCode.length + 1)
    }
    return code
  },
  set: (val: string) => {
    formData.value = {
      ...formData.value,
      agentCode: props.tenantCode ? props.tenantCode + '-' + val : val
    }
  }
})

/**
 * 弹窗内可选择的标签由标签表提供。
 */
const filteredTags = computed(() => catalogTags.value.filter(tag =>
  tag.toLowerCase().includes(tagSearch.value.trim().toLowerCase())
))

/**
 * 表单验证规则
 */
const rules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 100, message: '名称长度不能超过100个字符', trigger: 'blur' }
  ],
  agentCode: [
    { required: true, message: '请输入智能体编号', trigger: 'blur' },
    { max: 100, message: '智能体编号长度不能超过100个字符', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_-]+$/, message: '智能体编号只能包含字母、数字、下划线和连字符', trigger: 'blur' }
  ],
  description: [
    { required: true, message: '请输入描述', trigger: 'blur' },
    { max: 500, message: '描述长度不能超过500个字符', trigger: 'blur' }
  ]
}

/**
 * 每次打开时重新查询标签表，选择先保留在弹窗草稿中。
 */
async function loadTags() {
  tagLoading.value = true
  tagLoadFailed.value = false
  try {
    const response = await agentApi.listTags()
    catalogTags.value = [...new Set(response.data.data || [])]
  } catch {
    tagLoadFailed.value = true
  } finally {
    tagLoading.value = false
  }
}

function openTagModal() {
  draftTags.value = [...selectedTags.value]
  newTagName.value = ''
  tagSearch.value = ''
  tagModalOpen.value = true
  void loadTags()
}

function toggleTag(tag: string, checked: boolean) {
  draftTags.value = checked
    ? [...new Set([...draftTags.value, tag])]
    : draftTags.value.filter(item => item !== tag)
}

async function addTag() {
  const name = newTagName.value.trim()
  if (!name || tagAdding.value) return
  if (name.length > 6) {
    message.warning('标签长度不能超过6个字符')
    return
  }
  const existing = catalogTags.value.find(tag => tag === name)
  if (existing) {
    toggleTag(existing, true)
    newTagName.value = ''
    message.info('已选择现有标签')
    return
  }
  tagAdding.value = true
  try {
    const response = await agentApi.createTag(name)
    // 服务端返回规范化后的名称；兼容旧网关/代理只返回成功码的情况。
    const savedName = String(response.data.data || name).trim()
    if (!savedName) {
      throw new Error('标签创建响应缺少名称')
    }
    catalogTags.value = [...new Set([...catalogTags.value, savedName])]
    toggleTag(savedName, true)
    newTagName.value = ''
    message.success('标签已添加')
  } catch {
    // 请求层会显示失败原因，保留输入以便重试。
  } finally {
    tagAdding.value = false
  }
}

function removeTag(tag: string) {
  selectedTags.value = selectedTags.value.filter(item => item !== tag)
}

function confirmDeleteTag(tag: string) {
  Modal.confirm({
    title: '删除标签',
    content: `确定删除标签“${tag}”吗？删除后该标签将从租户标签目录中移除。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => deleteCatalogTag(tag)
  })
}

async function deleteCatalogTag(tag: string) {
  await agentApi.deleteTag(tag)
  catalogTags.value = catalogTags.value.filter(item => item !== tag)
  draftTags.value = draftTags.value.filter(item => item !== tag)
  selectedTags.value = selectedTags.value.filter(item => item !== tag)
  message.success('标签已删除')
}

function confirmTags() {
  selectedTags.value = [...draftTags.value]
  tagModalOpen.value = false
}

function closeTagModal() {
  tagModalOpen.value = false
}

/**
 * 验证表单
 */
async function validate(): Promise<boolean> {
  try {
    await formRef.value?.validate()
    return true
  } catch {
    return false
  }
}

defineExpose({
  validate
})
</script>

<template>
  <AForm
    ref="formRef"
    :model="formData"
    :rules="rules"
    layout="vertical"
    style="padding: 0 1px;">
    <AFormItem label="标签">
      <div class="tag-editor">
        <AButton class="tag-picker-trigger" @click="openTagModal">
          <PlusOutlined />
          {{ selectedTags.length ? '选择或管理标签' : '添加标签' }}
        </AButton>
        <div v-if="selectedTags.length" class="selected-tags">
          <span v-for="tag in selectedTags" :key="tag" class="selected-tag">
            <span>{{ tag }}</span>
            <button type="button" class="tag-remove" :aria-label="`移除标签 ${tag}`" title="移除标签" @click="removeTag(tag)">
              <CloseOutlined />
            </button>
          </span>
        </div>
      </div>
    </AFormItem>

    <AModal
      :open="tagModalOpen"
      title="选择与管理标签"
      :width="480"
      :mask-closable="false"
      :ok-button-props="{ disabled: tagLoading || tagLoadFailed || tagAdding }"
      ok-text="确定"
      @ok="confirmTags"
      @cancel="closeTagModal"
    >
      <div class="tag-modal-content">
        <AInput v-model:value="tagSearch" allow-clear placeholder="搜索已有标签" aria-label="搜索已有标签" />
        <div class="tag-catalog" :aria-busy="tagLoading">
          <ASpin v-if="tagLoading" class="tag-catalog-state" />
          <div v-else-if="tagLoadFailed" class="tag-catalog-state">
            <span>标签加载失败</span>
            <AButton type="link" @click="loadTags">重试</AButton>
          </div>
          <AEmpty v-else-if="!filteredTags.length" :description="tagSearch ? '没有匹配的标签' : '暂无标签'" />
          <template v-else>
            <div v-for="tag in filteredTags" :key="tag" class="tag-catalog-item">
              <ACheckbox
                :checked="draftTags.includes(tag)"
                @change="(event: { target: { checked: boolean } }) => toggleTag(tag, event.target.checked)"
              >{{ tag }}</ACheckbox>
              <AButton
                type="text"
                danger
                size="small"
                class="tag-catalog-delete"
                :aria-label="`删除标签 ${tag}`"
                title="删除标签"
                @click.stop="confirmDeleteTag(tag)"
              >
                <DeleteOutlined />
              </AButton>
            </div>
          </template>
        </div>
        <div class="tag-add-row">
          <AInput
            v-model:value="newTagName"
            :maxlength="6"
            placeholder="输入新标签"
            aria-label="输入新标签"
            @keydown.enter.prevent="addTag"
          />
          <AButton type="primary" :loading="tagAdding" :disabled="!newTagName.trim() || tagLoading || tagLoadFailed" @click="addTag">
            <template #icon><PlusOutlined /></template>
            新增
          </AButton>
        </div>
      </div>
    </AModal>

    <AFormItem label="名称" name="name">
      <AInput v-model:value="formData.name" placeholder="请输入智能体名称" />
    </AFormItem>

    <AFormItem v-if="showAgentCode !== false" name="agentCode">
      <template #label>
        <ATooltip title="“智能体编号”将会在agui接口和外置对话界面中使用，请谨慎填写。您修改“智能体编号”后需要重新生成“外置对话链接”。">
          <span>智能体编号</span><InfoCircleOutlined class="text-secondary cursor-pointer" />
        </ATooltip>
      </template>
      <AInput :prefix="tenantCode ? tenantCode + '-' : ''" v-model:value="agentCodeSuffix" placeholder="请输入智能体编号，如: my-agent-001" />
    </AFormItem>

    <AFormItem label="描述" name="description">
      <ATextarea
        v-model:value="formData.description"
        placeholder="请输入智能体描述"
        :rows="2"
      />
    </AFormItem>
  </AForm>
</template>

<style scoped lang="scss">
.tag-editor {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.tag-picker-trigger {
  flex: none;
}
.tag-modal-content {
  display: grid;
  gap: 12px;
}
.tag-catalog {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 120px;
  max-height: 240px;
  padding: 6px;
  overflow-y: auto;
  border: 1px solid var(--color-border-base);
  border-radius: 4px;
}
.tag-catalog-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 32px;
  margin-inline-start: 0;
  padding: 5px 8px;
}
.tag-catalog-item:hover {
  background: var(--color-bg-light);
}
.tag-catalog-item :deep(.ant-checkbox-wrapper) {
  flex: 1;
  min-width: 0;
}
.tag-catalog-delete {
  flex: none;
}
.tag-catalog-state {
  margin: auto;
}
.tag-add-row {
  display: flex;
  gap: 8px;
}
.tag-add-row :deep(.ant-input) {
  flex: 1;
  min-width: 0;
}
.selected-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.selected-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 26px;
  padding: 2px 6px 2px 9px;
  border: 1px solid var(--color-border-base);
  border-radius: 4px;
  background: var(--color-bg-light);
  color: var(--color-text-primary);
}
.tag-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
}
.tag-remove:hover {
  color: var(--color-primary);
}
:deep(.ant-input-prefix) {
  margin-inline-end: 0;
}
</style>
