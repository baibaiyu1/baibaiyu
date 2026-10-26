/**
 * 长期记忆配置选择组件
 * 支持选择、新增、编辑、删除操作
 *
 */
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Modal } from 'ant-design-vue'
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import type { LongTermMemoryConfigVO } from '@/types'
import * as longTermMemoryApi from '@/api/longTermMemory'
import LongTermMemoryConfigForm from './LongTermMemoryConfigForm.vue'

/**
 * Props定义
 */
const props = defineProps<{
  modelValue: string | null
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:modelValue': [value: string | null]
}>()

/**
 * 配置列表
 */
const configList = ref<LongTermMemoryConfigVO[]>([])

/**
 * 加载状态
 */
const loading = ref(false)

/**
 * 下拉框打开状态
 */
const dropdownVisible = ref(false)

/**
 * 表单弹窗状态
 */
const formVisible = ref(false)

/**
 * 当前编辑的数据
 */
const currentEditData = ref<LongTermMemoryConfigVO | undefined>(undefined)

/**
 * 选中的配置
 */
const selectedValue = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

/**
 * 选中的配置显示文本
 */
const selectedLabel = computed(() => {
  if (!selectedValue.value) return undefined
  const config = configList.value.find(c => c.id === selectedValue.value)
  if (!config) return undefined
  return config.configName
})

/**
 * 获取记忆类型标签
 */
function getMemoryTypeLabel(type: string) {
  const labels: Record<string, string> = {
    MEM0: 'Mem0',
    REME: 'ReMe',
    BAILIAN: '百炼'
  }
  return labels[type] || type
}

/**
 * 加载配置列表
 */
async function loadConfigList() {
  loading.value = true
  try {
    const response = await longTermMemoryApi.list()
    configList.value = (response.data.data || []) as LongTermMemoryConfigVO[]
  } catch (error) {
    console.error('加载配置列表失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 处理选择
 */
function handleSelect(value: string) {
  selectedValue.value = value
  dropdownVisible.value = false
}

/**
 * 处理清除
 */
function handleClear() {
  selectedValue.value = null
}

/**
 * 处理新增
 */
function handleAdd() {
  currentEditData.value = undefined
  formVisible.value = true
}

/**
 * 处理编辑
 */
function handleEdit(config: LongTermMemoryConfigVO, e: Event) {
  e.stopPropagation()
  currentEditData.value = { ...config }
  formVisible.value = true
}

/**
 * 处理删除
 */
async function handleDelete(config: LongTermMemoryConfigVO, e: Event) {
  e.stopPropagation()

  const used = await longTermMemoryApi.usedWithAgent([config.id])
  const usedAgents = used.data.data as string[] || []

  if (usedAgents.length > 0) {
    Modal.confirm({
      title: '二次确认',
      content: `该长期记忆配置正在被 [ ${usedAgents.join('、')} ] 智能体引用，删除后可能会影响上述智能体的正常使用！`,
      okText: '确认并继续删除',
      onOk: async () => {
        await longTermMemoryApi.remove([config.id])
        await loadConfigList()
        if (selectedValue.value === config.id) {
          selectedValue.value = null
        }
      }
    })
    return
  }

  Modal.confirm({
    title: '确认删除',
    content: '删除后无法恢复，是否继续?',
    onOk: async () => {
      await longTermMemoryApi.remove([config.id])
      await loadConfigList()
      if (selectedValue.value === config.id) {
        selectedValue.value = null
      }
    }
  })
}

/**
 * 表单提交成功
 */
function handleFormSuccess() {
  loadConfigList()
}

/**
 * 组件挂载时加载配置列表
 */
onMounted(() => {
  loadConfigList()
})
</script>

<template>
  <div class="long-term-memory-config-select">
    <ARow :gutter="16">
      <ACol :span="12">
        <ASelect
          v-model:value="selectedValue"
          :open="dropdownVisible"
          :loading="loading"
          :placeholder="selectedLabel || '请选择长期记忆配置'"
          option-label-prop="label"
          allow-clear
          @dropdown-visible-change="(visible: boolean) => dropdownVisible = visible"
          @clear="handleClear"
        >
          <template #dropdownRender="{ menuNode }">
            <div class="dropdown-content">
              <component :is="menuNode" />
              <ADivider style="margin: 4px 0" />
              <div class="dropdown-footer" @mousedown.prevent>
                <AButton type="text" size="small" @click="handleAdd">
                  <template #icon><PlusOutlined /></template>
                  新增配置
                </AButton>
              </div>
            </div>
          </template>

          <ASelectOption
            v-for="config in configList"
            :key="config.id"
            :value="config.id"
            :label="config.configName"
            @click="handleSelect(config.id)"
          >
            <div class="config-item">
              <div class="config-info">
                <div class="config-name">{{ config.configName }}</div>
                <div class="config-type text-placeholder text-xs">
                  {{ getMemoryTypeLabel(config.memoryType) }}
                </div>
              </div>
              <div class="config-actions" @click.stop>
                <AButton type="text" size="small" @click="handleEdit(config, $event)">
                  <template #icon><EditOutlined /></template>
                </AButton>
                <AButton type="text" size="small" danger @click="handleDelete(config, $event)">
                  <template #icon><DeleteOutlined /></template>
                </AButton>
              </div>
            </div>
          </ASelectOption>

          <template #notFoundContent>
            <div class="empty-content">
              <span class="text-placeholder">暂无配置</span>
              <AButton type="link" size="small" @click="handleAdd">
                <PlusOutlined /> 新增
              </AButton>
            </div>
          </template>
        </ASelect>
      </ACol>
    </ARow>

    <LongTermMemoryConfigForm
      v-model:visible="formVisible"
      :data="currentEditData"
      @success="handleFormSuccess"
    />
  </div>
</template>

<style scoped lang="scss">
.long-term-memory-config-select {
  width: 100%;
}

.dropdown-content {
  :deep(.ant-select-item) {
    padding: 8px 12px;
  }
}

.dropdown-footer {
  padding: 4px 8px;
  cursor: pointer;
}

.config-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.config-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.config-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-type {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.empty-content {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px;
}
</style>
