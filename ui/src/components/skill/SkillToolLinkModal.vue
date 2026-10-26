/**
 * 技能关联工具选择器弹窗组件
 *
 */
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import type { ToolVO } from '@/types'
import * as skillApi from '@/api/skill'
import * as toolApi from '@/api/tool'

/**
 * Props定义
 */
const props = defineProps<{
  visible: boolean
  skillId?: string
}>()

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: []
}>()

/** 工具列表 */
const allTools = ref<ToolVO[]>([])
const toolsLoading = ref(false)
/** 当前已选工具ID列表（编辑中的临时状态） */
const selectedToolIds = ref<string[]>([])
/** 左侧搜索文本 */
const leftToolSearch = ref('')
/** 右侧搜索文本 */
const rightToolSearch = ref('')
/** 保存中状态 */
const saving = ref(false)

/** 可选工具列表（排除已选） */
const availableTools = computed(() => {
  const unselected = allTools.value.filter(t => !selectedToolIds.value.includes(String(t.id)))
  if (!leftToolSearch.value) return unselected
  const search = leftToolSearch.value.toLowerCase()
  return unselected.filter(t =>
    t.name.toLowerCase().includes(search) ||
    t.description?.toLowerCase().includes(search) ||
    t.category?.toLowerCase().includes(search)
  )
})

/** 已选工具列表 */
const selectedTools = computed(() => {
  const selected = allTools.value.filter(t => selectedToolIds.value.includes(String(t.id)))
  if (!rightToolSearch.value) return selected
  const search = rightToolSearch.value.toLowerCase()
  return selected.filter(t =>
    t.name.toLowerCase().includes(search) ||
    t.description?.toLowerCase().includes(search) ||
    t.category?.toLowerCase().includes(search)
  )
})

/**
 * 加载所有工具列表
 */
async function loadAllTools() {
  try {
    toolsLoading.value = true
    const res = await toolApi.page({ page: 1, size: 1000, enabled: true })
    allTools.value = res.data.data.records || []
  } finally {
    toolsLoading.value = false
  }
}

/**
 * 添加工具到已选列表
 */
function addTool(toolId: string) {
  if (!selectedToolIds.value.includes(toolId)) {
    selectedToolIds.value.push(toolId)
  }
}

/**
 * 从已选列表移除工具
 */
function removeTool(toolId: string) {
  const idx = selectedToolIds.value.indexOf(toolId)
  if (idx > -1) {
    selectedToolIds.value.splice(idx, 1)
  }
}

/**
 * 保存关联工具
 */
async function handleConfirm() {
  if (!props.skillId) return
  try {
    saving.value = true
    await skillApi.saveTools(props.skillId, selectedToolIds.value)
    message.success('关联工具设置成功')
    emit('update:visible', false)
    emit('success')
  } catch {
    message.error('保存关联工具失败')
  } finally {
    saving.value = false
  }
}

/**
 * 取消
 */
function handleCancel() {
  emit('update:visible', false)
}

/**
 * 监听弹窗打开，加载数据
 */
watch(() => props.visible, async (val) => {
  if (val && props.skillId) {
    // 加载当前技能关联的工具ID
    try {
      const detailRes = await skillApi.detail(props.skillId)
      const vo = detailRes.data.data
      selectedToolIds.value = (vo?.tools || []).map(String)
    } catch {
      selectedToolIds.value = []
    }
    leftToolSearch.value = ''
    rightToolSearch.value = ''
    // 加载工具列表
    if (allTools.value.length === 0) {
      loadAllTools()
    }
  }
})
</script>

<template>
  <a-modal
    :open="visible"
    title="关联工具"
    width="780px"
    ok-text="确定"
    cancel-text="取消"
    :confirm-loading="saving"
    @ok="handleConfirm"
    @cancel="handleCancel"
    destroyOnClose
  >
    <a-spin :spinning="toolsLoading">
      <div v-if="allTools.length === 0 && !toolsLoading" class="empty-tools-tip">
        <span class="text-secondary">暂无可用工具</span>
      </div>
      <div v-else class="tool-selector">
        <!-- 左侧：可选工具 -->
        <div class="tool-selector__panel">
          <div class="tool-selector__header">
            <span class="tool-selector__title">可选工具 ({{ availableTools.length }})</span>
            <a-input
              v-model:value="leftToolSearch"
              placeholder="搜索工具..."
              allow-clear
              style="width: 140px; border: 1px solid var(--color-border-base) !important;"
            />
          </div>
          <div class="tool-selector__list">
            <div
              v-for="tool in availableTools"
              :key="tool.id"
              class="tool-item"
              @click="addTool(String(tool.id))"
            >
              <div class="tool-item__main">
                <span class="tool-item__name" :title="tool.name">{{ tool.name }}</span>
                <a-tag :bordered="false" size="small" color="blue">{{ tool.category }}</a-tag>
              </div>
              <div class="tool-item__desc" :title="tool.description || '暂无描述'">
                {{ tool.description || '暂无描述' }}
              </div>
            </div>
            <div v-if="availableTools.length === 0" class="tool-selector__empty">无匹配工具</div>
          </div>
        </div>
        <!-- 右侧：已选工具 -->
        <div class="tool-selector__panel">
          <div class="tool-selector__header">
            <span class="tool-selector__title">已选工具 ({{ selectedTools.length }})</span>
            <a-input
              v-model:value="rightToolSearch"
              placeholder="搜索工具..."
              allow-clear
              style="width: 140px; border: 1px solid var(--color-border-base) !important;"
            />
          </div>
          <div class="tool-selector__list">
            <div
              v-for="tool in selectedTools"
              :key="tool.id"
              class="tool-item tool-item--selected"
              @click="removeTool(String(tool.id))"
            >
              <div class="tool-item__main">
                <span class="tool-item__name" :title="tool.name">{{ tool.name }}</span>
                <a-tag :bordered="false" size="small" color="blue">{{ tool.category }}</a-tag>
              </div>
              <div class="tool-item__desc" :title="tool.description || '暂无描述'">
                {{ tool.description || '暂无描述' }}
              </div>
            </div>
            <div v-if="selectedTools.length === 0" class="tool-selector__empty">未选择工具</div>
          </div>
        </div>
      </div>
    </a-spin>
    <div class="tool-selector__tip">
      <span>点击工具可添加或移除关联关系，智能体选中 Skill 后，关联的工具会被自动注册。</span>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
.tool-selector {
  display: flex;
  gap: var(--spacing-md);
  min-height: 320px;

  &__panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    border: 1px solid var(--color-border-base, #e8e8e8);
    border-radius: var(--border-radius-md, 6px);
    overflow: hidden;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    background: var(--color-bg-light, #fafafa);
    border-bottom: 1px solid var(--color-border-base, #e8e8e8);
  }

  &__title {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-base, #333);
  }

  &__list {
    flex: 1;
    overflow-y: auto;
    max-height: 350px;
    padding: var(--spacing-xs, 4px);
  }

  &__tip {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    margin-top: 12px;
    padding: 8px 12px;
    background: var(--color-bg-light, #f8f9fa);
    border-radius: var(--border-radius-sm, 4px);
    font-size: 12px;
    color: var(--color-text-secondary, #666);
    line-height: 1.6;
  }
}

.tool-item {
  padding: 8px 10px;
  border-radius: var(--border-radius-sm, 4px);
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background-color: var(--color-bg-light, #f5f5f5);
  }

  &--selected {
    // background-color: var(--color-primary-bg, #e6f7ff);
  }

  &__main {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    margin-bottom: 2px;
  }

  &__name {
    flex: 1;
    min-width: 0;
    font-size: 13px;
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__desc {
    font-size: 12px;
    color: var(--color-text-secondary, #999);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.empty-tools-tip {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 200px;
}

.tool-selector__empty {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 120px;
  color: var(--color-text-secondary, #999);
  font-size: 13px;
}
</style>
