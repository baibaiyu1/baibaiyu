/**
 * 智能体工具与能力表单组件
 *
 * 交互参考「青云Agent平台」的工作台：每类资源卡片都是「空态 / 已选 pill + 添加」，
 * 「添加」打开资源选择弹窗（搜索 + 双列卡片 + 勾选），确认后整表回写；
 * 细粒度参数（调用策略 / 指定工具 / 敏感词过滤）收进「高级设置」折叠区，
 * 避免出现「每个字段一行」的长表单。
 *
 */
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import Sortable from 'sortablejs'
import {
  AppstoreAddOutlined,
  CloseOutlined,
  HolderOutlined,
  PartitionOutlined,
  PlusOutlined,
  SettingOutlined,
  StopOutlined,
  SwapOutlined,
  ThunderboltOutlined,
  ToolOutlined
} from '@ant-design/icons-vue'
import { RouteNames } from '@/router/constants.ts'
import * as toolApi from '@/api/tool'
import * as skillApi from '@/api/skill'
import * as sensitiveApi from '@/api/sensitive'
import * as hookApi from '@/api/hook'
import * as workflowApi from '@/api/workflow'
import type { ToolVO, SkillPackageVO, SensitiveWordConfigVO, HookConfigVO, Workflow } from '@/types'
import { ToolChoiceStrategy } from '@/types'
import type { StudioSummary } from '@/components/agent/studio/summary'
import AgentResourcePicker, { type AgentPickerItem } from '@/components/agent/studio/AgentResourcePicker.vue'

const router = useRouter()

/**
 * Props定义
 */
const props = withDefaults(defineProps<{
  modelValue: {
    hook: string[]
    toolChoiceStrategy: ToolChoiceStrategy
    tool: string[]
    specificToolName: string
    workflow: string[]
    skill: string[]
    sensitiveWordConfigId: string
    sensitiveFilterEnabled: boolean
  }
  /**
   * 只渲染指定的资源分组（编排页把每类资源放进独立卡片，
   * Agent 设置弹窗的「安全策略」只挂 hook + sensitive）
   */
  groups?: ToolResourceKey[]
  /** 是否显示「工具选择策略 / 指定工具 / 敏感词过滤」折叠区 */
  showAdvanced?: boolean
}>(), {
  groups: undefined,
  showAdvanced: false
})

/**
 * Emits定义
 */
const emit = defineEmits<{
  'update:modelValue': [value: typeof props.modelValue]
  /** 候选资源（工具 / 钩子 / 工作流 / 技能包 / 敏感词）加载完成，通知编排页刷新概览 */
  ready: []
}>()

const formRef = ref()
const loading = ref(false)

const allTools = ref<ToolVO[]>([])
const allHooks = ref<HookConfigVO[]>([])
const allSkills = ref<SkillPackageVO[]>([])
const allSensitives = ref<SensitiveWordConfigVO[]>([])
const allWorkflows = ref<Workflow[]>([])

/**
 * 表单数据
 */
const formData = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

/**
 * 工具选择策略选项
 */
const strategyOptions = [
  { label: '自动', value: ToolChoiceStrategy.AUTO },
  { label: '不使用', value: ToolChoiceStrategy.NONE },
  { label: '必须使用', value: ToolChoiceStrategy.REQUIRED },
  { label: '指定工具', value: ToolChoiceStrategy.SPECIFIC }
]

/* ===================== 资源分组（空态 / 已选 pill / 选择弹窗） ===================== */

/** 资源分组标识 */
type ToolResourceKey = 'hook' | 'tool' | 'workflow' | 'skill' | 'sensitive'

/** 资源分组展示顺序 */
const resourceOrder: ToolResourceKey[] = ['hook', 'tool', 'workflow', 'skill', 'sensitive']

/** 实际渲染的分组（未指定 = 全部） */
const activeGroups = computed<ToolResourceKey[]>(() => {
  const requested = props.groups
  if (!requested?.length) return resourceOrder
  return resourceOrder.filter(key => requested.includes(key))
})

/** 当前打开的选择弹窗（null = 未打开） */
const activePicker = ref<ToolResourceKey | null>(null)

/** 高级设置折叠区（默认收起） */
const advancedKeys = ref<string[]>([])

/** 各分组的标题 / 说明 / 空态文案 / 搜索占位 */
const pickerMeta: Record<ToolResourceKey, {
  label: string
  title: string
  desc: string
  emptyTitle: string
  emptyDesc: string
  addText: string
  searchPlaceholder: string
  moreText: string
  configRoute: string
}> = {
  hook: {
    label: '钩子配置',
    title: '添加钩子',
    desc: '在推理链路的关键节点插入自定义逻辑（如思考块过滤、工具执行确认）。',
    emptyTitle: '暂无添加钩子',
    emptyDesc: '添加钩子可在关键节点拦截或改写智能体的行为。',
    addText: '添加钩子',
    searchPlaceholder: '搜索钩子名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.HOOK
  },
  tool: {
    label: '工具集',
    title: '添加工具',
    desc: '关联外部工具，扩展智能体的行动能力与调用策略。',
    emptyTitle: '暂无添加工具',
    emptyDesc: '添加工具，让智能体能查询数据、调用外部系统。',
    addText: '添加工具',
    searchPlaceholder: '搜索工具名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.TOOL
  },
  workflow: {
    label: '工作流',
    title: '添加工作流',
    desc: '关联工作流，为智能体提供流程编排与自动化能力。',
    emptyTitle: '暂无添加工作流',
    emptyDesc: '添加已发布的工作流，让智能体按固定流程执行任务。',
    addText: '添加工作流',
    searchPlaceholder: '搜索工作流名称 / 备注',
    moreText: '继续添加',
    configRoute: RouteNames.WORKFLOW
  },
  skill: {
    label: '技能包',
    title: '添加技能包',
    desc: '关联技能包，为智能体赋予组合能力与业务流程。',
    emptyTitle: '暂无添加技能包',
    emptyDesc: '添加技能包，让智能体按预设步骤完成复杂任务。',
    addText: '添加技能包',
    searchPlaceholder: '搜索技能包名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.SKILL
  },
  sensitive: {
    label: '敏感词配置',
    title: '选择敏感词配置',
    desc: '选择一套敏感词规则，对智能体的输入与输出做过滤（单选）。',
    emptyTitle: '暂无敏感词配置',
    emptyDesc: '选择敏感词配置后，可开启输入输出的敏感词过滤。',
    addText: '选择敏感词配置',
    searchPlaceholder: '搜索敏感词配置名称 / 描述',
    moreText: '更换配置',
    configRoute: RouteNames.SENSITIVE
  }
}

/** 各分组的空态图标 */
const resourceIcons: Record<ToolResourceKey, unknown> = {
  hook: ThunderboltOutlined,
  tool: ToolOutlined,
  workflow: PartitionOutlined,
  skill: AppstoreAddOutlined,
  sensitive: StopOutlined
}

/** 各分组的候选项（弹窗用：名称 + 描述 + 标签） */
const pickerItems = computed<Record<ToolResourceKey, AgentPickerItem[]>>(() => ({
  hook: allHooks.value.map(hook => ({
    id: String(hook.id),
    name: hook.name,
    desc: hook.description,
    badge: hook.hookType === 'BUILTIN' ? '内置' : '自定义',
    disabled: !hook.enabled,
    disabledReason: '已停用'
  })),
  tool: allTools.value.map(tool => ({
    id: String(tool.id),
    name: tool.name,
    desc: tool.description,
    badge: tool.category
  })),
  workflow: allWorkflows.value.map(workflow => ({
    id: String(workflow.id),
    name: workflow.name || String(workflow.id),
    desc: workflow.remark,
    badge: workflow.version ? `v${workflow.version}` : undefined
  })),
  skill: allSkills.value.map(skill => ({
    id: String(skill.id),
    name: skill.alias || skill.name,
    desc: skill.description,
    badge: skill.category
  })),
  sensitive: allSensitives.value.map(sensitive => ({
    id: String(sensitive.id),
    name: sensitive.name,
    desc: sensitive.description,
    badge: sensitive.category
  }))
}))

/** 各分组当前已选 id（敏感词为单选，用数组承载以复用弹窗） */
const pickerSelected = computed<Record<ToolResourceKey, string[]>>(() => ({
  hook: formData.value.hook || [],
  tool: formData.value.tool || [],
  workflow: formData.value.workflow || [],
  skill: formData.value.skill || [],
  sensitive: formData.value.sensitiveWordConfigId && formData.value.sensitiveWordConfigId !== '-1'
    ? [formData.value.sensitiveWordConfigId]
    : []
}))

/** 已选钩子（保持选择顺序，用于拖拽排序展示） */
const selectedHooks = computed(() => {
  const map = new Map(allHooks.value.map(item => [String(item.id), item]))
  return (formData.value.hook || []).map(id => map.get(id)).filter((item): item is HookConfigVO => !!item)
})

/** 已选工具（保持选择顺序） */
const selectedTools = computed(() => {
  const map = new Map(allTools.value.map(item => [String(item.id), item]))
  return (formData.value.tool || []).map(id => map.get(id)).filter((item): item is ToolVO => !!item)
})

/** 已选工作流 */
const selectedWorkflows = computed(() => {
  const map = new Map(allWorkflows.value.map(item => [String(item.id), item]))
  return (formData.value.workflow || []).map(id => map.get(id)).filter((item): item is Workflow => !!item)
})

/** 已选工作流名称（workflow.name 可选，回落到 id） */
function workflowName(workflow: Workflow): string {
  return workflow.name || String(workflow.id)
}

/** 已选技能包 */
const selectedSkills = computed(() => {
  const map = new Map(allSkills.value.map(item => [String(item.id), item]))
  return (formData.value.skill || []).map(id => map.get(id)).filter((item): item is SkillPackageVO => !!item)
})

/** 已选敏感词配置（单选） */
const selectedSensitive = computed(() =>
  allSensitives.value.find(item => String(item.id) === formData.value.sensitiveWordConfigId)
)

/** 各分组已选数量 */
const selectedCount = computed<Record<ToolResourceKey, number>>(() => ({
  hook: selectedHooks.value.length,
  tool: selectedTools.value.length,
  workflow: selectedWorkflows.value.length,
  skill: selectedSkills.value.length,
  sensitive: selectedSensitive.value ? 1 : 0
}))

/**
 * 打开资源选择弹窗
 * @param key 资源分组
 */
function openPicker(key: ToolResourceKey) {
  activePicker.value = key
}

/**
 * 弹窗确认：整表回写选中集
 * @param key 资源分组
 * @param ids 确认后的 id 列表
 */
function handlePickerConfirm(key: ToolResourceKey, ids: string[]) {
  if (key === 'sensitive') {
    const next = ids[0]
    formData.value = next
      ? { ...formData.value, sensitiveWordConfigId: next, sensitiveFilterEnabled: true }
      : { ...formData.value, sensitiveWordConfigId: '-1', sensitiveFilterEnabled: false }
    return
  }
  if (key === 'hook') {
    // 钩子有执行顺序：回写后重建拖拽实例
    formData.value = { ...formData.value, hook: [...ids] }
    void nextTick(() => {
      destroyHookSortable()
      initHookSortable()
    })
    return
  }
  formData.value = { ...formData.value, [key]: [...ids] }
}

/**
 * 移除单个已选项
 * @param key 资源分组
 * @param id 资源 id
 */
function removeResource(key: ToolResourceKey, id: string) {
  handlePickerConfirm(key, (pickerSelected.value[key] || []).filter(item => item !== id))
}

/* ===================== 钩子拖拽排序 ===================== */

const selectedHooksListRef = ref<HTMLElement | null>(null)
let sortableInstance: ReturnType<typeof Sortable.create> | null = null

/**
 * 初始化钩子列表拖拽排序
 */
function initHookSortable() {
  if (!selectedHooksListRef.value || selectedHooks.value.length === 0) return
  if (sortableInstance) return

  sortableInstance = Sortable.create(selectedHooksListRef.value, {
    animation: 150,
    handle: '.hook-drag-handle',
    ghostClass: 'hook-sortable-ghost',
    chosenClass: 'hook-sortable-chosen',
    dragClass: 'hook-sortable-drag',
    forceFallback: false,
    fallbackClass: 'hook-sortable-fallback',
    preventOnFilter: false,
    onStart: () => {
      document.body.classList.add('dragging')
    },
    onEnd: async (evt: { oldIndex?: number; newIndex?: number }) => {
      document.body.classList.remove('dragging')
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
      const newOrder = [...formData.value.hook]
      const [item] = newOrder.splice(oldIndex, 1)
      if (item === undefined) return
      newOrder.splice(newIndex, 0, item)
      formData.value = { ...formData.value, hook: newOrder }
      // 拖拽后销毁并重新初始化 Sortable，确保 DOM 与 Vue 数据同步
      destroyHookSortable()
      await nextTick()
      initHookSortable()
    }
  })
}

/**
 * 销毁拖拽实例
 */
function destroyHookSortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
}

/**
 * 是否显示特定工具选择
 */
const showSpecificTool = computed(() => {
  return formData.value.toolChoiceStrategy === ToolChoiceStrategy.SPECIFIC
})

/* ===================== 候选资源加载 ===================== */

/**
 * 加载所有工具
 */
async function loadAllTools() {
  const response = await toolApi.page({ page: 1, size: 1000, enabled: true })
  allTools.value = response.data.data.records || []
}

/**
 * 加载所有技能包
 */
async function loadAllSkills() {
  const response = await skillApi.page({ page: 1, size: 1000, enabled: true })
  allSkills.value = response.data.data.records || []
}

/**
 * 加载所有敏感词
 */
async function loadAllSensitives() {
  const response = await sensitiveApi.page({ page: 1, size: 1000, enabled: true })
  allSensitives.value = response.data.data.records || []
}

/**
 * 加载所有钩子
 */
async function loadAllHooks() {
  const response = await hookApi.page({ page: 1, size: 1000 })
  allHooks.value = response.data.data.records || []
}

/**
 * 加载所有已发布启用的工作流
 */
async function loadAllWorkflows() {
  const response = await workflowApi.workflowPage({ page: 1, size: 1000, status: 'PUBLISHED', enabled: true })
  allWorkflows.value = response.data.data.records || []
}

/**
 * 刷新候选资源（空态里的「去配置」旁边用不到，保留给弹窗内刷新场景）
 */
async function reloadAll() {
  const loaders: Record<ToolResourceKey, () => Promise<void>> = {
    hook: loadAllHooks,
    tool: loadAllTools,
    workflow: loadAllWorkflows,
    skill: loadAllSkills,
    sensitive: loadAllSensitives
  }
  loading.value = true
  try {
    // 每张卡只请求实际展示的候选资源。
    await Promise.all(activeGroups.value.map(key => loaders[key]()))
    emit('ready')
  } finally {
    loading.value = false
  }
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

/**
 * 编排概览：当前已选的钩子 / 工具 / 工作流 / 技能包 / 敏感词名称
 * （供编排页锚点条显示数量，避免重复请求）
 */
function getSummary(): StudioSummary {
  const groups: StudioSummary = {
    hook: { label: '钩子', names: selectedHooks.value.map(item => item.name) },
    tool: { label: '工具', names: selectedTools.value.map(item => item.name) },
    workflow: { label: '工作流', names: selectedWorkflows.value.map(item => workflowName(item)) },
    skill: { label: '技能包', names: selectedSkills.value.map(item => item.alias || item.name) }
  }
  if (selectedSensitive.value) {
    groups.sensitive = { label: '敏感词', names: [selectedSensitive.value.name] }
  }
  // 只上报本次实际渲染的分组，避免同一份表单被多处挂载时重复计数
  return Object.fromEntries(
    Object.entries(groups).filter(([key]) => activeGroups.value.includes(key as ToolResourceKey))
  )
}

watch(
  () => selectedHooks.value.length,
  async (len) => {
    destroyHookSortable()
    if (len > 0) {
      await nextTick()
      initHookSortable()
    }
  }
)

onMounted(async () => {
  await reloadAll()
  if (selectedHooks.value.length > 0) {
    await nextTick()
    initHookSortable()
  }
})

onUnmounted(() => {
  destroyHookSortable()
})

defineExpose({
  validate,
  getSummary,
  reloadAll
})
</script>

<template>
  <AppSpin :spinning="loading">
    <AForm ref="formRef" :model="formData" layout="vertical">
      <!-- 资源分组：空态 / 已选 pill + 添加（青云式） -->
      <AFormItem
        v-for="key in activeGroups"
        :key="key"
        :label="pickerMeta[key].label"
      >
        <!-- 已选：pill 列表 -->
        <template v-if="selectedCount[key] > 0">
          <div class="agstudio-res-pills">
            <div v-if="key === 'hook'" ref="selectedHooksListRef" class="agstudio-res-pills__list">
              <span
                v-for="hook in selectedHooks"
                :key="hook.id"
                class="agstudio-res-pill agstudio-res-pill--drag"
                :title="hook.name"
              >
                <span class="hook-drag-handle" title="拖拽调整执行顺序">
                  <HolderOutlined class="hook-drag-handle-icon" />
                </span>
                <span class="agstudio-res-pill__text">{{ hook.name }}</span>
                <em>{{ hook.enabled ? (hook.hookType === 'BUILTIN' ? '内置' : '自定义') : '已停用' }}</em>
                <button type="button" title="移除" @click.stop="removeResource('hook', String(hook.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>

            <div v-else-if="key === 'tool'" class="agstudio-res-pills__list">
              <span v-for="tool in selectedTools" :key="tool.id" class="agstudio-res-pill" :title="tool.name">
                <span class="agstudio-res-pill__text">{{ tool.name }}</span>
                <button type="button" title="移除" @click.stop="removeResource('tool', String(tool.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>

            <div v-else-if="key === 'workflow'" class="agstudio-res-pills__list">
              <span
                v-for="workflow in selectedWorkflows"
                :key="workflow.id"
                class="agstudio-res-pill"
                :title="workflowName(workflow)"
              >
                <span class="agstudio-res-pill__text">{{ workflowName(workflow) }}</span>
                <button type="button" title="移除" @click.stop="removeResource('workflow', String(workflow.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>

            <div v-else-if="key === 'skill'" class="agstudio-res-pills__list">
              <span
                v-for="skill in selectedSkills"
                :key="skill.id"
                class="agstudio-res-pill"
                :title="skill.alias || skill.name"
              >
                <span class="agstudio-res-pill__text">{{ skill.alias || skill.name }}</span>
                <button type="button" title="移除" @click.stop="removeResource('skill', String(skill.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>

            <div v-else class="agstudio-res-pills__list">
              <span
                v-if="selectedSensitive"
                class="agstudio-res-pill"
                :title="selectedSensitive.name"
              >
                <span class="agstudio-res-pill__text">{{ selectedSensitive.name }}</span>
                <button
                  type="button"
                  title="移除"
                  @click.stop="removeResource('sensitive', String(selectedSensitive.id))"
                >
                  <CloseOutlined />
                </button>
              </span>
            </div>

            <button type="button" class="agstudio-res-add-more" @click="openPicker(key)">
              <SwapOutlined v-if="key === 'sensitive'" />
              <PlusOutlined v-else />
              {{ pickerMeta[key].moreText }}
            </button>
          </div>

          <div v-if="key === 'hook'" class="text-placeholder text-xs mt-sm">
            拖拽 pill 可调整钩子的执行顺序。
          </div>
          <div v-else-if="key === 'tool'" class="text-placeholder text-xs mt-sm">
            共 {{ selectedTools.length }} 个工具；调用策略在下方「高级设置」中调整。
          </div>
        </template>

        <!-- 空态：插画 + 说明 + 添加 -->
        <div v-else class="agstudio-res-empty">
          <span class="agstudio-res-empty__icon">
            <component :is="resourceIcons[key]" />
          </span>
          <div class="agstudio-res-empty__copy">
            <strong>{{ pickerMeta[key].emptyTitle }}</strong>
            <p>{{ pickerMeta[key].emptyDesc }}</p>
          </div>
          <div class="agstudio-res-empty__actions">
            <button type="button" class="agstudio-res-empty__add" @click="openPicker(key)">
              <PlusOutlined />{{ pickerMeta[key].addText }}
            </button>
            <a
              class="agstudio-res-empty__link"
              :href="router.resolve({ name: pickerMeta[key].configRoute }).href"
              target="_blank"
            >去配置</a>
          </div>
        </div>
      </AFormItem>

      <!-- 细粒度参数：默认收起 -->
      <ACollapse v-if="showAdvanced" v-model:activeKey="advancedKeys" class="agstudio-res-advanced">
        <ACollapsePanel key="advanced">
          <template #header>
            <span class="agstudio-res-advanced__title">
              <SettingOutlined />高级设置
              <em>工具调用策略 / 指定工具 / 敏感词过滤</em>
            </span>
          </template>

          <AFormItem v-if="formData.tool?.length > 0" label="工具选择策略">
            <ARadioGroup v-model:value="formData.toolChoiceStrategy" :options="strategyOptions" />
            <div v-if="formData.toolChoiceStrategy === ToolChoiceStrategy.REQUIRED || showSpecificTool" class="text-placeholder text-xs mt-xs">
              DeepSeek 开启推理或使用模型默认时，必须使用按自动选择执行；指定工具只提供所选工具，由模型决定是否调用。需要强制调用时，请使用支持关闭推理的模型并关闭推理。
            </div>
          </AFormItem>

          <AFormItem v-if="showSpecificTool" label="指定工具">
            <ASelect v-model:value="formData.specificToolName" placeholder="选择一个工具">
              <ASelectOption v-for="tool in selectedTools" :key="tool.toolId" :value="tool.toolId">
                {{ tool.name }}
              </ASelectOption>
            </ASelect>
            <div class="text-placeholder text-xs mt-xs">
              从已选择的工具中指定一个工具作为默认工具
            </div>
          </AFormItem>

          <AFormItem
            v-if="formData.sensitiveWordConfigId && formData.sensitiveWordConfigId !== '-1'"
            label="启用敏感词过滤"
          >
            <ASwitch v-model:checked="formData.sensitiveFilterEnabled" />
            <div class="text-placeholder text-xs mt-xs">
              开启后，将对输入和输出进行敏感词过滤
            </div>
          </AFormItem>

          <div
            v-if="!formData.tool?.length && !showSpecificTool && (!formData.sensitiveWordConfigId || formData.sensitiveWordConfigId === '-1')"
            class="text-placeholder text-xs"
          >
            选择工具后可配置调用策略与默认工具；选择敏感词配置后可开启过滤。
          </div>
        </ACollapsePanel>
      </ACollapse>
    </AForm>
  </AppSpin>

  <!-- 资源选择弹窗：搜索 + 双列卡片，点选切换，确认整表回写 -->
  <AgentResourcePicker
    v-if="activePicker"
    :open="true"
    :title="pickerMeta[activePicker].title"
    :description="pickerMeta[activePicker].desc"
    :items="pickerItems[activePicker]"
    :selected-ids="pickerSelected[activePicker]"
    :multiple="activePicker !== 'sensitive'"
    :search-placeholder="pickerMeta[activePicker].searchPlaceholder"
    :empty-text="pickerMeta[activePicker].emptyTitle"
    @update:open="(value: boolean) => { if (!value) activePicker = null }"
    @confirm="(ids: string[]) => handlePickerConfirm(activePicker as ToolResourceKey, ids)"
  />
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-resource.scss' as *;

.hook-drag-handle {
  display: flex;
  align-items: center;
  padding: 0 1px;
  cursor: grab;

  &:active {
    cursor: grabbing;
  }

  .hook-drag-handle-icon {
    color: #9aa8bc;
    font-size: 13px;

    &:hover {
      color: #1677ff;
    }
  }
}

:deep(.hook-sortable-ghost) {
  opacity: 0.5;
  background-color: #f0f0f0;
  border: 1px dashed #1890ff;
}

:deep(.hook-sortable-chosen) {
  background-color: #fafafa;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

:deep(.hook-sortable-drag) {
  opacity: 0.9;
  transform: rotate(2deg);
  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.3);
}

:deep(.hook-sortable-fallback) {
  opacity: 1 !important;
}

/* 防止拖拽时页面滚动 */
:global(body.dragging) {
  user-select: none;
  -webkit-user-select: none;
}
</style>
