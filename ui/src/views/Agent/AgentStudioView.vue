<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台（Agent Studio）
 *
 * 页面形态参考「青云Agent平台」的智能体编辑页：
 * 左侧导航（名片 + 分区）+ 顶部操作条（保存脏标记 / 发布）+ 中间卡片式编排 + 右侧预览与调试。
 *
 * 设计取舍：
 * - 布局取青云形态，**字段与校验完全复用既有 AgentForm* 子表单**，不丢失任何配置能力；
 * - 中间区不照抄青云的「单页卡片瀑布」，保留 5 张分组卡，用**卡片锚点条**（sticky + 当前卡高亮）
 *   与**编排概览卡**（已选资源 pill 汇总）承接青云「空态 / 已选 pill」的版式语言；
 * - 概览数据由各子表单的 `getSummary()` 汇总（`studio/summary.ts`），避免重复请求候选列表；
 * - 「定时」分区挂载既有的 AgentConfigSchedule（此前在配置面板中未挂载，属不可达页）；
 * - 发布走审批流，外部游客访问另由管理员显式开启。
 *
 * @component
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Component } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import type { MenuProps } from 'ant-design-vue'
import {
  ApartmentOutlined,
  ApiOutlined,
  AppstoreAddOutlined,
  AppstoreOutlined,
  BarChartOutlined,
  BookOutlined,
  ClockCircleOutlined,
  HistoryOutlined,
  LinkOutlined,
  MessageOutlined,
  PartitionOutlined,
  ReloadOutlined,
  RobotOutlined,
  SaveOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  ToolOutlined
} from '@ant-design/icons-vue'
import { storeToRefs } from 'pinia'
import { useAccountStore, useAgentStore } from '@/stores'
import { RouteNames } from '@/router/constants'
import * as agentApi from '@/api/agent'
import * as approvalApi from '@/api/approval'
import type { AgentDefinitionVO } from '@/types'
import { ToolChoiceStrategy } from '@/types'
import AgentFormModel from '@/components/agent/AgentFormModel.vue'
import AgentFormTools from '@/components/agent/AgentFormTools.vue'
import AgentFormKnowledge from '@/components/agent/AgentFormKnowledge.vue'
import AgentConfigEditA2a from '@/components/agent/config/AgentConfigEditA2a.vue'
import AgentConfigArchitecture from '@/components/agent/config/AgentConfigArchitecture.vue'
import AgentConfigHistory from '@/components/agent/config/AgentConfigHistory.vue'
import AgentConfigApiDoc from '@/components/agent/config/AgentConfigApiDoc.vue'
import AgentConfigStatistics from '@/components/agent/config/AgentConfigStatistics.vue'
import AgentConfigSchedule from '@/components/agent/config/AgentConfigSchedule.vue'
import AgentStudioSidebar from '@/components/agent/studio/AgentStudioSidebar.vue'
import AgentStudioCard from '@/components/agent/studio/AgentStudioCard.vue'
import AgentStudioPreview from '@/components/agent/studio/AgentStudioPreview.vue'
import AgentSettingsModal, { type AgentSettingsDraft, type AgentSettingsSection } from '@/components/agent/studio/AgentSettingsModal.vue'
import AgentModelSelect from '@/components/agent/studio/AgentModelSelect.vue'
import AgentModelParamsPopover from '@/components/agent/studio/AgentModelParamsPopover.vue'
import AgentWebAppFeaturesModal from '@/components/agent/studio/AgentWebAppFeaturesModal.vue'
import * as modelApi from '@/api/model'
import type { ModelConfigVO } from '@/types'
import { summaryTotal, type StudioSummary } from '@/components/agent/studio/summary'

/** 分区标识 */
type SectionKey = 'workbench' | 'schedule' | 'architecture' | 'api' | 'logs' | 'stats'

const route = useRoute()
const router = useRouter()
const agentStore = useAgentStore()
const { tags } = storeToRefs(agentStore)
const { currentTenant } = useAccountStore()

const agentId = computed(() => String(route.params.id ?? ''))
const agentData = ref<AgentDefinitionVO>()
const loading = ref(true)
const saving = ref(false)
const publishing = ref(false)
const dirty = ref(false)
const activeSection = ref<SectionKey>('workbench')

const basicFormRef = ref()
const modelFormRef = ref()
const hookFormRef = ref()
const toolsFormRef = ref()
const skillFormRef = ref()
const workflowFormRef = ref()
const knowledgeFormRef = ref()
const mcpFormRef = ref()
const subAgentFormRef = ref()
const advancedFormRef = ref()
const a2aFormRef = ref()
const securityFormRef = ref()

/** 是否为 A2A 远端智能体 */
const isA2a = computed(() => agentData.value?.agentType === 'A2A')

/** 已加载的智能体（用于需要非空类型的子组件） */
const currentAgent = computed(() => agentData.value as AgentDefinitionVO)

/** 模型列表（顶栏「选模型」与「模型参数」需要模型默认值） */
const models = ref<ModelConfigVO[]>([])

/** 当前选中的模型配置 */
const currentModel = computed(() =>
  models.value.find(item => String(item.id) === String(formData.value.model.modelConfigId))
)

/**
 * 加载模型列表（顶栏模型选择器展示用）
 */
async function loadModels() {
  try {
    const response = await modelApi.configPage({ page: 1, size: 1000, enabled: true } as never)
    models.value = response.data.data.records || []
  } catch (e) {
    console.error('加载模型列表失败:', e)
  }
}

/* ===================== Agent 设置弹窗 ===================== */

const settingsOpen = ref(false)
const settingsSection = ref<AgentSettingsSection>('basic')

/** 「功能增强 · Web App 用户体验」弹窗 */
const featuresOpen = ref(false)

/** 对话端展示配置（开场白 / 推荐问题） */
const uiConfig = computed(() => agentData.value?.uiConfig ?? null)

/**
 * 打开 Agent 设置弹窗
 * @param section 打开后定位到的分区
 */
function openSettings(section: AgentSettingsSection = 'basic') {
  settingsSection.value = section
  settingsOpen.value = true
}

/** Agent 设置弹窗保存：校验后应用草稿，保存失败则恢复编排页原值。 */
async function handleSettingsSave(draft: AgentSettingsDraft) {
  const previous = {
    basic: formData.value.basic,
    tools: formData.value.tools,
    advanced: formData.value.advanced,
    modelParamsOverride: formData.value.model.modelParamsOverride
  }
  formData.value.basic = draft.basic as typeof formData.value.basic
  formData.value.tools = draft.tools as typeof formData.value.tools
  formData.value.advanced = draft.advanced as typeof formData.value.advanced
  if ('modelParamsOverride' in draft) formData.value.model.modelParamsOverride = draft.modelParamsOverride || null
  const ok = await save()
  if (ok) {
    settingsOpen.value = false
  } else {
    formData.value.basic = previous.basic
    formData.value.tools = previous.tools
    formData.value.advanced = previous.advanced
    formData.value.model.modelParamsOverride = previous.modelParamsOverride
  }
}

/**
 * 顶栏「模型参数」气泡回写覆盖参数
 * @param value 覆盖参数（null = 取消覆盖）
 */
function handleParamsOverride(value: Record<string, unknown> | null) {
  formData.value.model.modelParamsOverride = value
}

/**
 * 卡片头部的「跟随模板更新」开关，复用提示词表单的同步与请求失效处理。
 * @param value 是否跟随
 */
function handleFollowTemplateChange(value: boolean) {
  modelFormRef.value?.setFollowTemplate?.(value)
}

/** 卡片头的模板下拉选项（由 AgentFormModel 暴露，避免重复请求模板列表） */
const promptTemplateOptions = computed(() => modelFormRef.value?.templateOptions || [])

/**
 * 卡片头选择提示词模板
 * @param promptId 模板 id
 */
function handleTemplateChange(promptId?: string) {
  modelFormRef.value?.selectTemplate?.(promptId)
}

/**
 * 编排概览：由各子表单（加载完候选资源后）汇总出的「已选资源名称」，
 * 供卡片锚点条与概览卡渲染青云式的「已选 pill + 数量徽标」。
 */
const summary = ref<StudioSummary>({})

/** 卡片锚点：卡片 key + 用于数量徽标的概览分组 + 显示名（分组为空表示不显示数量） */
const cardAnchors: Array<{ key: string; summaryKeys: string[]; label: string }> = [
  { key: 'prompt', summaryKeys: ['promptTemplate'], label: '提示词' },
  { key: 'tool', summaryKeys: ['tool'], label: '工具' },
  { key: 'skill', summaryKeys: ['skill'], label: '技能包' },
  { key: 'workflow', summaryKeys: ['workflow'], label: '工作流' },
  { key: 'knowledge', summaryKeys: ['knowledge'], label: '知识库' },
  { key: 'mcp', summaryKeys: ['mcp'], label: 'MCP 服务' },
  { key: 'subAgent', summaryKeys: ['subAgent'], label: '子智能体' }
]

/** 卡片 ref 容器（模板中 :ref 写入） */
const cardRefs = ref<Record<string, HTMLElement | null>>({})

/**
 * 卡片 ref 回调：把卡片根节点写入容器（供锚点滚动），并打上 data-card-key（供 IntersectionObserver 回查）
 * @param key 卡片 key
 * @param el 卡片组件实例或根元素
 */
function setCardRef(key: string, el: unknown) {
  const element = (el as { $el?: HTMLElement } | null)?.$el ?? (el as HTMLElement | null)
  cardRefs.value[key] = element ?? null
  element?.setAttribute('data-card-key', key)
}

/** 中心区滚动容器（IntersectionObserver 的 root） */
const centerRef = ref<HTMLElement | null>(null)

/**
 * 已选资源分组（仅用于锚点条的数量徽标汇总）
 */
const summaryGroups = computed(() =>
  Object.entries(summary.value)
    .map(([key, group]) => ({ key, ...group }))
    .filter(group => group.names.length > 0)
)

/** 已配置的资源项总数 */
const configuredCount = computed(() =>
  summaryGroups.value.reduce((total, group) => total + group.names.length, 0)
)

/**
 * 从各子表单拉取编排概览（子表单未就绪时静默跳过）
 * @param flush 是否等待挂载后的下一帧（刚由 loading 切到表单时需要）
 */
function refreshSummary(flush = false) {
  const collect = () => {
    const parts = [
      modelFormRef.value?.getSummary?.(),
      hookFormRef.value?.getSummary?.(),
      toolsFormRef.value?.getSummary?.(),
      skillFormRef.value?.getSummary?.(),
      workflowFormRef.value?.getSummary?.(),
      knowledgeFormRef.value?.getSummary?.(),
      mcpFormRef.value?.getSummary?.(),
      subAgentFormRef.value?.getSummary?.()
    ]
    const next: StudioSummary = {}
    for (const part of parts) {
      if (!part) continue
      Object.assign(next, part)
    }
    summary.value = next
  }
  if (flush) void nextTick(collect)
  else collect()
}

/**
 * 卡片锚点条目：分区标签 + 已配置数量（仅工作台分区展示）
 */
const anchorItems = computed(() => {
  if (activeSection.value !== 'workbench' || isA2a.value) return []
  return cardAnchors.map(item => ({
    key: item.key,
    label: item.label,
    count: summaryTotal(summary.value, item.summaryKeys)
  }))
})

/** 当前滚到视野顶部的卡片（锚点高亮） */
const activeCardKey = ref('basic')

function handleCardIntersect(entries: IntersectionObserverEntry[]) {
  const visible = entries.filter(entry => entry.isIntersecting)
  if (!visible.length) return
  const key = visible[0]?.target.getAttribute('data-card-key')
  if (key) activeCardKey.value = key
}

/** 点击锚点滚动到对应卡片 */
function scrollToCard(key: string) {
  const el = cardRefs.value[key]
  if (!el) return
  activeCardKey.value = key
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 分区列表（架构图仅自定义智能体可见） */
const sections = computed(() => {
  const items: Array<{ key: string; label: string; icon: Component }> = [
    { key: 'workbench', label: '工作台', icon: AppstoreOutlined }
  ]
  if (!isA2a.value) {
    items.push({ key: 'architecture', label: '架构', icon: ApartmentOutlined })
  }
  items.push(
    { key: 'schedule', label: '定时', icon: ClockCircleOutlined },
    { key: 'api', label: 'API', icon: ApiOutlined },
    { key: 'logs', label: '日志', icon: HistoryOutlined },
    { key: 'stats', label: '统计', icon: BarChartOutlined }
  )
  return items
})

/**
 * 表单元数据（与既有 5 步表单保持一致，避免字段丢失）
 */
const formData = ref({
  basic: {
    name: '',
    agentCode: '',
    description: '',
    tag: '',
    tags: [] as string[]
  },
  model: {
    modelConfigId: '',
    modelParamsOverride: null as Record<string, unknown> | null,
    systemPromptTemplateId: '',
    followTemplate: true,
    systemPrompt: ''
  },
  tools: {
    hook: [] as string[],
    toolChoiceStrategy: ToolChoiceStrategy.AUTO,
    tool: [] as string[],
    specificToolName: '',
    workflow: [] as string[],
    skill: [] as string[],
    codeExecutionConfigId: null as string | null,
    sensitiveWordConfigId: '',
    sensitiveFilterEnabled: false
  },
  knowledge: {
    knowledgeBase: [] as string[],
    mcp: [] as string[],
    mcpBindings: [] as AgentDefinitionVO['mcpBindings'],
    subAgent: [] as string[],
    ragConfig: null as Record<string, unknown> | null
  },
  advanced: {
    modelResilienceConfig: null as AgentDefinitionVO['modelResilienceConfig'],
    enablePlanning: false,
    showPlanMode: true,
    showModelUsage: true,
    maxIterations: 50,
    maxSubtasks: 10,
    requirePlanConfirmation: false,
    enableMemory: true,
    showToolProcess: false,
    enableMemoryCompression: false,
    memoryCompressionConfig: null as Record<string, unknown> | null,
    structuredOutputEnabled: false,
    structuredOutputReminder: 'TOOL_CHOICE' as 'TOOL_CHOICE' | 'PROMPT',
    structuredOutputSchema: '',
    studioConfigId: null as string | null,
    codeExecutionConfigId: null as string | null,
    longTermMemoryConfigId: null as string | null
  }
})

/**
 * 由服务端数据初始化表单
 */
function initFormData() {
  const data = agentData.value
  if (!data) return

  formData.value.basic = {
    name: data.name,
    agentCode: data.agentCode,
    description: data.description,
    tag: data.tag || '',
    tags: data.tags ?? (data.tag ? [data.tag] : [])
  }
  formData.value.model = {
    modelConfigId: data.modelConfigId,
    modelParamsOverride: data.modelParamsOverride || null,
    systemPromptTemplateId: data.systemPromptTemplateId,
    followTemplate: data.followTemplate,
    systemPrompt: data.systemPrompt
  }
  formData.value.tools = {
    hook: data.hook || [],
    toolChoiceStrategy: data.toolChoiceStrategy,
    tool: data.tool || [],
    specificToolName: data.specificToolName || '',
    workflow: data.workflow || [],
    skill: data.skill || [],
    sensitiveWordConfigId: data.sensitiveWordConfigId || '',
    codeExecutionConfigId: data.codeExecutionConfigId || null,
    sensitiveFilterEnabled: data.sensitiveFilterEnabled
  }
  formData.value.knowledge = {
    knowledgeBase: data.knowledgeBase || [],
    mcp: data.mcp || [],
    mcpBindings: data.mcpBindings || [],
    subAgent: data.subAgent || [],
    ragConfig: data.ragConfig || null
  }
  formData.value.advanced = {
    modelResilienceConfig: data.modelResilienceConfig || null,
    enablePlanning: data.enablePlanning,
    showPlanMode: data.uiConfig?.showPlanMode !== false,
    showModelUsage: data.uiConfig?.showModelUsage !== false,
    maxIterations: data.maxIterations || 50,
    maxSubtasks: data.maxSubtasks || 10,
    requirePlanConfirmation: data.requirePlanConfirmation,
    enableMemory: data.enableMemory,
    showToolProcess: data.showToolProcess,
    enableMemoryCompression: data.enableMemoryCompression,
    memoryCompressionConfig: data.memoryCompressionConfig || null,
    structuredOutputEnabled: data.structuredOutputEnabled,
    structuredOutputReminder: data.structuredOutputReminder || 'TOOL_CHOICE',
    structuredOutputSchema: data.structuredOutputSchema
      ? JSON.stringify(data.structuredOutputSchema, null, 2)
      : '',
    studioConfigId: data.studioConfigId || null,
    codeExecutionConfigId: data.codeExecutionConfigId || null,
    longTermMemoryConfigId: data.longTermMemoryConfigId || null
  }

  nextTick(() => {
    takeDirtySnapshot()
  })
}

/* ===================== 脏标记（基线比对） =====================
 *
 * 不能只靠 `watch(formData, deep)` 直接置脏：各子表单在挂载后会**规范化**自己那部分数据
 * （例如 MCP 绑定由 mcp 数组补齐、ragConfig 预填默认值、模型参数表单回填），
 * 这些是「加载副作用」而非用户改动，直接置脏会导致什么都没改也提示「有未保存的更改」。
 * 因此这里保存一份「加载完成时的基线」，之后按值比较。
 */

/** 参与脏检查的字段（只取真正会提交的字段，避免表单辅助字段误判） */
function dirtyComparableValue() {
  const data = formData.value
  return {
    basic: data.basic,
    model: data.model,
    tools: data.tools,
    knowledge: data.knowledge,
    advanced: data.advanced
  }
}

/**
 * 稳定的序列化：对象按键排序，数组保留顺序（钩子执行顺序等属于实际配置）。
 * @param value 待序列化的值
 */
function stableStringify(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (Array.isArray(value)) {
    return `[${value.map(item => stableStringify(item)).join(',')}]`
  }
  if (typeof value === 'object') {
    return `{${Object.keys(value as Record<string, unknown>)
      .sort()
      .map(key => `${JSON.stringify(key)}:${stableStringify((value as Record<string, unknown>)[key])}`)
      .join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

/** 脏检查基线 */
const dirtyBaseline = ref('')

/** 记录当前状态为基线并清除脏标记 */
function takeDirtySnapshot() {
  dirtyBaseline.value = stableStringify(dirtyComparableValue())
  dirty.value = false
}

/** 是否与基线一致 */
function isFormDirty(): boolean {
  return stableStringify(dirtyComparableValue()) !== dirtyBaseline.value
}

// 值变化时才复核（子表单的规范化写回不会改变值 → 不会置脏）
watch(formData, () => {
  dirty.value = isFormDirty()
}, { deep: true, flush: 'post' })

// 概览随勾选变化实时刷新（flush: 'post' 保证子表单已完成本次渲染）
watch(formData, () => refreshSummary(), { deep: true, flush: 'post' })

/**
 * 加载智能体详情
 * @param silent 静默刷新（不切换到整页 loading）
 */
async function load(silent = false) {
  if (!agentId.value) return
  if (!silent) loading.value = true
  try {
    const response = await agentApi.detail(agentId.value)
    agentData.value = response.data.data
    initFormData()
    // 子表单挂载后才会拉取候选资源，故下一帧再汇总「已选资源」
    refreshSummary(true)
  } catch (e) {
    console.error('加载智能体配置失败:', e)
    message.error('加载智能体配置失败')
  } finally {
    loading.value = false
  }
}

/**
 * 保存全部表单
 */
async function save(): Promise<boolean> {
  const data = agentData.value
  if (!data) return false

  // 逐个校验工作台已挂载的子表单；设置弹窗在提交草稿前先自行校验。
  const forms = [
    basicFormRef.value,
    modelFormRef.value,
    hookFormRef.value,
    toolsFormRef.value,
    skillFormRef.value,
    workflowFormRef.value,
    knowledgeFormRef.value,
    mcpFormRef.value,
    subAgentFormRef.value,
    securityFormRef.value,
    advancedFormRef.value
  ]
  for (const form of forms) {
    const valid = await form?.validate?.()
    if (valid === false) {
      message.warning('请先完善表单校验后再保存')
      return false
    }
  }

  saving.value = true
  try {
    const vo: Partial<AgentDefinitionVO> = {
      id: data.id,
      enabled: data.enabled,
      name: formData.value.basic.name,
      agentType: 'CUSTOM',
      agentCode: formData.value.basic.agentCode,
      description: formData.value.basic.description,
      tag: formData.value.basic.tag || '',
      tags: formData.value.basic.tags,
      modelConfigId: formData.value.model.modelConfigId,
      modelParamsOverride: formData.value.model.modelParamsOverride,
      modelResilienceConfig: formData.value.advanced.modelResilienceConfig,
      systemPromptTemplateId: formData.value.model.systemPromptTemplateId,
      followTemplate: formData.value.model.followTemplate,
      systemPrompt: formData.value.model.systemPrompt,
      toolChoiceStrategy: formData.value.tools.toolChoiceStrategy,
      tool: formData.value.tools.tool,
      specificToolName: formData.value.tools.specificToolName,
      workflow: formData.value.tools.workflow,
      skill: formData.value.tools.skill,
      sensitiveWordConfigId: formData.value.tools.sensitiveWordConfigId,
      sensitiveFilterEnabled: formData.value.tools.sensitiveFilterEnabled,
      knowledgeBase: formData.value.knowledge.knowledgeBase,
      mcp: formData.value.knowledge.mcp,
      mcpBindings: formData.value.knowledge.mcpBindings,
      subAgent: formData.value.knowledge.subAgent,
      ragConfig: formData.value.knowledge.ragConfig,
      hook: formData.value.tools.hook,
      enablePlanning: formData.value.advanced.enablePlanning,
      uiConfig: {
        ...data.uiConfig,
        showPlanMode: formData.value.advanced.showPlanMode,
        showModelUsage: formData.value.advanced.showModelUsage
      },
      maxIterations: formData.value.advanced.maxIterations,
      maxSubtasks: formData.value.advanced.maxSubtasks,
      requirePlanConfirmation: formData.value.advanced.requirePlanConfirmation && formData.value.advanced.enableMemory,
      showToolProcess: formData.value.advanced.showToolProcess,
      enableMemory: formData.value.advanced.enableMemory,
      enableMemoryCompression: formData.value.advanced.enableMemoryCompression,
      memoryCompressionConfig: formData.value.advanced.memoryCompressionConfig,
      structuredOutputEnabled: formData.value.advanced.structuredOutputEnabled,
      structuredOutputReminder: formData.value.advanced.structuredOutputReminder,
      structuredOutputSchema: formData.value.advanced.structuredOutputEnabled && formData.value.advanced.structuredOutputSchema
        ? JSON.parse(formData.value.advanced.structuredOutputSchema)
        : null,
      studioConfigId: formData.value.advanced.studioConfigId,
      codeExecutionConfigId: formData.value.advanced.codeExecutionConfigId,
      longTermMemoryConfigId: formData.value.advanced.longTermMemoryConfigId,
      version: '1.0.0'
    }

    await agentApi.update(vo as AgentDefinitionVO)
    message.success('保存成功')
    dirty.value = false
    agentStore.fetchTags()
    await load(true)
    return true
  } catch (e) {
    console.error('保存失败:', e)
    return false
  } finally {
    saving.value = false
  }
}

/**
 * 有未保存内容时二次确认
 */
function guardUnsaved(action: () => void) {
  if (!dirty.value) {
    action()
    return
  }
  Modal.confirm({
    title: '未保存的更改',
    content: '当前有未保存的更改，继续将丢失这些更改。是否继续？',
    okText: '继续',
    cancelText: '留在当前',
    onOk: action
  })
}

/**
 * 分区切换 / 返回
 *
 * 注意：**板块之间切换不做未保存拦截**——同一份表单始终挂载，切到「定时 / 架构 / API / 日志 / 统计」
 * 只是换视图、不会丢草稿（未保存提示只在「离开工作台/整页」时才有意义，见 onBeforeRouteLeave）。
 * 以前这里走 guardUnsaved，导致什么都没改也会被弹窗打断。
 */
function selectSection(key: string) {
  if (key === 'back') {
    guardUnsaved(() => router.push({ name: RouteNames.AGENT }))
    return
  }
  if (key === 'edit') {
    openSettings('basic')
    return
  }
  if (key === activeSection.value) return
  activeSection.value = key as SectionKey
}

/**
 * 放弃更改并重新加载
 */
function reload() {
  guardUnsaved(() => load())
}

/**
 * Ctrl / Cmd + S 保存（避免浏览器「保存网页」默认行为打断编排）
 */
function handleSaveShortcut(e: KeyboardEvent) {
  if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 's') return
  e.preventDefault()
  if (isA2a.value || saving.value) return
  void save()
}

onMounted(() => window.addEventListener('keydown', handleSaveShortcut))

onBeforeUnmount(() => window.removeEventListener('keydown', handleSaveShortcut))

/**
 * 打开对话页（新标签）
 */
function goVisit() {
  const hash = `#/chat/${encodeURIComponent(agentId.value)}`
  window.open(`${window.location.origin}${window.location.pathname}${hash}`, '_blank')
}

/**
 * 提交发布申请
 *
 * 由后端按「智能体」的审批流配置决定：已启用审批则进入待审批队列，
 * 未启用审批则直接发布生效（返回 autoApproved）。
 */
async function submitPublish() {
  const data = agentData.value
  if (!data) return
  if (dirty.value) {
    message.warning('请先保存当前更改')
    return
  }
  publishing.value = true
  try {
    const response = await approvalApi.submit({
      resourceType: 'AGENT',
      resourceId: String(data.id),
      resourceName: data.name,
      resourceVersion: data.version
    })
    const result = response.data.data
    if (result?.autoApproved) {
      message.success(result.message || '未启用审批，已直接发布')
    } else {
      message.success(result?.message || '已提交发布审批')
    }
    await load(true)
  } catch (e) {
    console.error('提交发布申请失败:', e)
  } finally {
    publishing.value = false
  }
}

/**
 * 取消发布（下架）：直接停用，不进入审批
 */
async function unpublish() {
  if (dirty.value) {
    message.warning('请先保存当前更改')
    return
  }
  await agentStore.toggleEnabled(agentId.value, false)
  message.success('已取消发布')
  await load(true)
}

/** 发布菜单 */
const publishItems = computed<MenuProps['items']>(() => [
  {
    key: 'publish',
    label: '提交发布审批',
    disabled: agentData.value?.published === true && agentData.value?.enabled === true
  },
  {
    key: 'unpublish',
    label: '取消发布',
    disabled: agentData.value?.enabled !== true
  },
  { type: 'divider' },
  {
    key: 'visit',
    label: '打开对话页'
  }
])

function handlePublishClick(info: { key: string | number }) {
  switch (String(info.key)) {
    case 'publish':
      void submitPublish()
      break
    case 'unpublish':
      void unpublish()
      break
    case 'visit':
      goVisit()
      break
    default:
      break
  }
}

/**
 * 子页面操作成功（如定时任务保存）后静默刷新详情
 */
function handleSubSuccess() {
  void agentStore.fetchTags()
  void load(true)
}

/** 卡片滚动监听（锚点高亮） */
let cardObserver: IntersectionObserver | null = null

function setupCardObserver() {
  cardObserver?.disconnect()
  if (!centerRef.value) return
  // rootMargin 顶部留出锚点条的高度，避免「刚滚过一半」就被判为当前卡片
  cardObserver = new IntersectionObserver(handleCardIntersect, {
    root: centerRef.value,
    rootMargin: '-88px 0px -55% 0px',
    threshold: 0
  })
  cardAnchors.forEach(item => {
    const el = cardRefs.value[item.key]
    if (el) cardObserver?.observe(el)
  })
}

onMounted(() => {
  agentStore.fetchTags()
  void loadModels()
  void load().then(() => {
    void nextTick(setupCardObserver)
  })
})

onBeforeUnmount(() => {
  cardObserver?.disconnect()
  cardObserver = null
})

/**
 * 离开编排页（返回列表 / 浏览器前进后退）时才拦截未保存：保存机制只作用于「整页离开」，
 * 板块之间的切换不弹窗（同一份草稿还在）。
 */
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '未保存的更改',
      content: '当前有未保存的更改，离开将丢失这些更改。是否继续？',
      okText: '继续离开',
      cancelText: '留在当前',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  })
})

/**
 * 关标签页 / 刷新 / 直接改地址：浏览器只认 beforeunload（路由守卫覆盖不到这些场景）。
 * 有未保存改动时挂上提示，保存或离开后摘掉。
 */
function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (!dirty.value) return
  e.preventDefault()
  // 兼容旧浏览器：需要设置 returnValue 才会弹原生确认
  e.returnValue = ''
}

watch(dirty, (value) => {
  if (value) window.addEventListener('beforeunload', handleBeforeUnload)
  else window.removeEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<template>
  <div class="agstudio">
    <AgentStudioSidebar
      :agent-name="agentData?.name || '智能体'"
      :agent-description="agentData?.description"
      :agent-type="agentData?.agentType"
      :enabled="agentData?.enabled"
      :active-section="activeSection"
      :items="sections"
      @select="selectSection"
    />

    <div class="agstudio-main">
      <header class="agstudio-topbar">
        <div class="agstudio-topbar__title">
          <div class="agstudio-topbar__copy">
            <strong>
              {{ agentData?.name || '智能体编排' }}
              <span class="agstudio-topbar__badge-pill">模块化编辑</span>
            </strong>
            <small>
              正在编辑：{{ agentData?.name || '—' }} · {{ agentData?.agentCode || '—' }}
              <template v-if="!isA2a"> · 已挂载 {{ configuredCount }} 项能力</template>
            </small>
          </div>
        </div>

        <div class="agstudio-topbar__actions">
          <!-- Agent 设置（基础信息 / 安全策略 / 高级配置） -->
          <a-button v-if="!isA2a" @click="openSettings()">
            <template #icon><SettingOutlined /></template>
            Agent 设置
          </a-button>

          <!-- 选模型 + 模型参数（与参照页顶栏一致） -->
          <template v-if="!isA2a">
            <AgentModelSelect v-model="formData.model.modelConfigId" />
            <AgentModelParamsPopover
              :form-data="formData.model"
              :model="currentModel"
              @update:override="handleParamsOverride"
            />
          </template>

          <a-button v-if="!isA2a" type="primary" ghost @click="goVisit">
            <template #icon><MessageOutlined /></template>
            去对话
          </a-button>
          <a-button v-else @click="goVisit">
            <template #icon><MessageOutlined /></template>
            去对话
          </a-button>

          <template v-if="!isA2a">
            <a-button :loading="saving" @click="save">
              <template #icon><SaveOutlined /></template>
              {{ dirty ? '保存更改' : '保存' }}
            </a-button>

            <a-dropdown :trigger="['click']">
              <a-button type="primary" :loading="publishing">
                <template #icon><ThunderboltOutlined /></template>
                发布
              </a-button>
              <template #overlay>
                <a-menu :items="publishItems" @click="handlePublishClick" />
              </template>
            </a-dropdown>
          </template>

          <a-tooltip title="重新加载（放弃未保存更改）">
            <a-button :loading="loading" @click="reload">
              <template #icon><ReloadOutlined /></template>
            </a-button>
          </a-tooltip>
        </div>
      </header>

      <div class="agstudio-content">
        <main ref="centerRef" class="agstudio-center">
          <div v-if="loading" class="agstudio-state">
            <a-spin />
            <span>正在加载智能体配置…</span>
          </div>

          <div v-else-if="!agentData" class="agstudio-state">
            <SettingOutlined class="agstudio-state__icon" />
            <span>未找到该智能体，可能已被删除</span>
            <a-button @click="router.push({ name: RouteNames.AGENT })">返回列表</a-button>
          </div>

          <template v-else>
            <template v-if="activeSection === 'workbench'">
              <!-- 卡片锚点条（青云式：分区跳转 + 已配置数量） -->
              <nav v-if="anchorItems.length" class="agstudio-anchors" aria-label="配置分区导航">
                <button
                  v-for="item in anchorItems"
                  :key="item.key"
                  class="agstudio-anchors__item"
                  :class="{ 'is-active': activeCardKey === item.key }"
                  @click="scrollToCard(item.key)"
                >
                  <span>{{ item.label }}</span>
                  <em v-if="item.count">{{ item.count }}</em>
                </button>
              </nav>

              <!-- 工作台：卡片式编排 -->
              <div class="agstudio-cards">
                <template v-if="isA2a">
                  <AgentStudioCard
                    title="A2A 智能体配置"
                    description="远端 A2A 智能体（WellKnown / Nacos）接入配置"
                    :icon="LinkOutlined"
                  >
                    <AgentConfigEditA2a
                      ref="a2aFormRef"
                      :agent-data="currentAgent"
                      :tags="tags"
                      @success="handleSubSuccess"
                    />
                  </AgentStudioCard>
                </template>

                <template v-else>
                  <!-- 提示词：模板 + 系统提示词（模型选择与模型参数在顶栏，见参照页） -->
                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('prompt', el)"
                    title="提示词"
                    description="选择系统提示词模板并维护任务指令，告诉模型如何为你服务。"
                    :icon="RobotOutlined"
                  >
                    <template #action>
                      <div class="agstudio-prompt-head">
                        <label class="agstudio-switch">
                          <span>跟随模板更新</span>
                          <a-switch
                            :checked="formData.model.followTemplate"
                            size="small"
                            @change="(v: any) => handleFollowTemplateChange(!!v)"
                          />
                        </label>
                        <a-select
                          :value="formData.model.systemPromptTemplateId || undefined"
                          class="agstudio-prompt-head__select"
                          size="small"
                          :options="promptTemplateOptions"
                          placeholder="选择提示词模板"
                          show-search
                          option-filter-prop="label"
                          allow-clear
                          @change="(v: any) => handleTemplateChange(v)"
                        />
                      </div>
                    </template>
                    <AgentFormModel
                      ref="modelFormRef"
                      v-model="formData.model"
                      prompt-only
                      @ready="refreshSummary()"
                    />
                  </AgentStudioCard>

                  <!-- 钩子/敏感词与高级配置统一收进「Agent 设置」，中间区不再重复 -->
                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('tool', el)"
                    title="工具"
                    description="关联外部工具，扩展智能体的行动能力与调用策略。"
                    :icon="ToolOutlined"
                  >
                    <AgentFormTools
                      ref="toolsFormRef"
                      v-model="formData.tools"
                      :groups="['tool']"
                      show-advanced
                      @ready="refreshSummary()"
                    />
                  </AgentStudioCard>

                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('skill', el)"
                    title="技能包"
                    description="关联技能包，为智能体赋予组合能力与业务流程。"
                    :icon="AppstoreAddOutlined"
                  >
                    <AgentFormTools ref="skillFormRef" v-model="formData.tools" :groups="['skill']" @ready="refreshSummary()" />
                  </AgentStudioCard>

                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('workflow', el)"
                    title="工作流"
                    description="关联工作流，为智能体提供流程编排与自动化能力。"
                    :icon="PartitionOutlined"
                  >
                    <AgentFormTools ref="workflowFormRef" v-model="formData.tools" :groups="['workflow']" @ready="refreshSummary()" />
                  </AgentStudioCard>

                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('knowledge', el)"
                    title="知识库"
                    description="关联企业知识库，为智能体提供可信的信息来源。"
                    :icon="BookOutlined"
                  >
                    <AgentFormKnowledge
                      ref="knowledgeFormRef"
                      v-model="formData.knowledge"
                      :current-agent-id="agentData?.id"
                      :groups="['knowledge']"
                      show-advanced
                      @ready="refreshSummary()"
                    />
                  </AgentStudioCard>

                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('mcp', el)"
                    title="MCP 服务"
                    description="关联 MCP 服务，让智能体通过标准协议调用外部能力。"
                    :icon="ApiOutlined"
                  >
                    <AgentFormKnowledge
                      ref="mcpFormRef"
                      v-model="formData.knowledge"
                      :current-agent-id="agentData?.id"
                      :groups="['mcp']"
                      @ready="refreshSummary()"
                    />
                  </AgentStudioCard>

                  <AgentStudioCard
                    :ref="(el: unknown) => setCardRef('subAgent', el)"
                    title="子智能体"
                    description="关联协作智能体，构建多智能体协同工作流。"
                    :icon="ApartmentOutlined"
                  >
                    <AgentFormKnowledge
                      ref="subAgentFormRef"
                      v-model="formData.knowledge"
                      :current-agent-id="agentData?.id"
                      :groups="['subAgent']"
                      @ready="refreshSummary()"
                    />
                  </AgentStudioCard>
                </template>
              </div>
            </template>

            <!-- 定时 -->
            <div v-else-if="activeSection === 'schedule'" class="agstudio-subpage">
              <AgentConfigSchedule
                :agent-id="agentId"
                :agent-name="agentData?.name"
                @success="handleSubSuccess"
              />
            </div>

            <!-- 架构 -->
            <div v-else-if="activeSection === 'architecture'" class="agstudio-subpage">
              <AgentConfigArchitecture :agent-id="agentId" />
            </div>

            <!-- API -->
            <div v-else-if="activeSection === 'api'" class="agstudio-subpage">
              <AgentConfigApiDoc
                :agent-id="agentId"
                :agent-code="agentData?.agentCode || ''"
                :enabled="agentData?.enabled"
                :published="agentData?.published"
                :external-share-enabled="agentData?.externalShareEnabled"
                @changed="load(true)"
              />
            </div>

            <!-- 日志 -->
            <div v-else-if="activeSection === 'logs'" class="agstudio-subpage">
              <AgentConfigHistory :agent-id="agentId" />
            </div>

            <!-- 统计 -->
            <div v-else-if="activeSection === 'stats'" class="agstudio-subpage">
              <AgentConfigStatistics :agent-id="agentId" />
            </div>
          </template>
        </main>

        <AgentStudioPreview
          v-if="!loading && agentData && activeSection === 'workbench'"
          :agent-id="String(agentData.id)"
          :agent-code="agentData.agentCode"
          :agent-name="agentData.name"
          :agent-description="agentData.description"
          :memory-active="agentData.enableMemory"
          :show-tool-process="agentData.showToolProcess"
          :enable-planning="agentData.enablePlanning"
          :show-thinking="agentData.reasoningConfig?.showThinking !== false"
          :enabled="agentData.enabled"
          :dirty="dirty"
          :opening-message="uiConfig?.openingMessage"
          :opening-questions="uiConfig?.openingQuestions"
          @open-features="featuresOpen = true"
        />
      </div>
    </div>

    <!-- 功能增强 · Web App 用户体验（开场白 / 推荐问题） -->
    <AgentWebAppFeaturesModal
      v-if="!isA2a && agentData"
      v-model:open="featuresOpen"
      :agent-id="String(agentData.id)"
      :opening-text="uiConfig?.openingMessage"
      :questions="uiConfig?.openingQuestions"
      @saved="handleSubSuccess"
    />

    <!-- Agent 设置：基础信息 / 安全策略 / 高级配置（时间轴导航） -->
    <AgentSettingsModal
      v-if="!isA2a"
      v-model:open="settingsOpen"
      :basic="formData.basic"
      :tools="formData.tools"
      :advanced="formData.advanced"
      :tags="tags"
      :tenant-code="currentTenant?.tenantCode"
      :model-config-id="formData.model.modelConfigId"
      :model="currentModel"
      :model-params-override="formData.model.modelParamsOverride"
      :initial-section="settingsSection"
      :saving="saving"
      @save="handleSettingsSave"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio.scss' as *;
</style>
