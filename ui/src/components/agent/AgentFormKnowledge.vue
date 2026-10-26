/**
 * 智能体知识库与 MCP 表单组件
 *
 * 交互与「工具与能力」卡一致：知识库 / MCP 服务 / 子智能体都是
 * 「空态 / 已选 pill + 添加」，添加走资源选择弹窗；RAG 检索参数与
 * MCP 的工具暴露配置分别收在「知识库调用策略」与单个 MCP 的配置弹窗里。
 *
 */
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RouteNames } from '@/router/constants.ts'
import * as knowledgeApi from '@/api/knowledge'
import * as mcpApi from '@/api/mcp'
import * as agentApi from '@/api/agent'
import type {
  KnowledgeBaseConfigVO,
  McpServerVO,
  AgentDefinitionVO,
  AgentMcpBindingVO,
  McpToolVO
} from '@/types'
import { McpActivationStatus, McpToolExposureMode, RAGMode } from '@/types'
import {
  ApartmentOutlined,
  ApiOutlined,
  BookOutlined,
  CloseOutlined,
  PlusOutlined,
  SettingOutlined
} from '@ant-design/icons-vue'
import type { StudioSummary } from '@/components/agent/studio/summary'
import AgentResourcePicker, { type AgentPickerItem } from '@/components/agent/studio/AgentResourcePicker.vue'
import AgentKnowledgeStrategy from './AgentKnowledgeStrategy.vue'
import { knowledgeStrategyError, type AgentKnowledgeStrategy as KnowledgeStrategy } from '@/utils/agentKnowledge'
import { message } from 'ant-design-vue'
import {
  getMcpConnectionStatusColor,
  getMcpConnectionStatusText,
  getMcpUnavailableReason
} from '@/composables/useMcpPresentation'

interface KnowledgeFormModel {
  knowledgeBase: string[]
  mcp: string[]
  mcpBindings: AgentMcpBindingVO[]
  subAgent: string[]
  /** 当前智能体的知识库调用、LightRAG 检索与证据预算。 */
  ragConfig: Record<string, unknown> | null
}

/** 资源分组标识 */
type KnowledgeResourceKey = 'knowledge' | 'mcp' | 'subAgent'

/**
 * Props 定义
 */
const props = withDefaults(defineProps<{
  modelValue: KnowledgeFormModel
  currentAgentId?: string | number | undefined
  /** 只渲染指定的资源分组（编排页每类资源一张卡） */
  groups?: KnowledgeResourceKey[]
  /** 是否显示知识库调用策略摘要与配置入口 */
  showAdvanced?: boolean
}>(), {
  groups: undefined,
  showAdvanced: false
})

/**
 * Emits 定义
 */
const emit = defineEmits<{
  'update:modelValue': [value: KnowledgeFormModel]
  /** 候选资源（知识库 / MCP 服务 / 子智能体）加载完成，通知编排页刷新「已选资源」概览 */
  ready: []
}>()

const formRef = ref()
const router = useRouter()
const loading = ref(false)
const toolLoadingMap = ref<Record<string, boolean>>({})

const allKnowledgeBases = ref<KnowledgeBaseConfigVO[]>([])
const allMcpServers = ref<McpServerVO[]>([])
const allAgents = ref<AgentDefinitionVO[]>([])
const mcpToolMap = ref<Record<string, McpToolVO[]>>({})

/**
 * 表单数据
 */
const formData = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

/* ===================== 资源分组（空态 / 已选 pill / 选择弹窗） ===================== */

/** 资源分组展示顺序 */
const resourceOrder: KnowledgeResourceKey[] = ['knowledge', 'mcp', 'subAgent']

/** 实际渲染的分组（未指定 = 全部） */
const activeGroups = computed<KnowledgeResourceKey[]>(() => {
  const requested = props.groups
  if (!requested?.length) return resourceOrder
  return resourceOrder.filter(key => requested.includes(key))
})

/** 当前打开的选择弹窗（null = 未打开） */
const activePicker = ref<KnowledgeResourceKey | null>(null)

const knowledgeStrategyRef = ref<InstanceType<typeof AgentKnowledgeStrategy>>()

/** 正在配置工具暴露的 MCP（null = 未打开） */
const configMcpId = ref<string | null>(null)

/**
 * 打开 MCP 工具治理页（工具级确认在那里维护）
 */
function goMcpGovernance() {
  const mcpId = configMcpId.value
  if (!mcpId) return
  window.open(router.resolve({ name: RouteNames.MCP_TOOL_GOVERNANCE, params: { serverId: mcpId } }).href, '_blank')
}

/** 各分组的标题 / 说明 / 空态文案 */
const pickerMeta: Record<KnowledgeResourceKey, {
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
  knowledge: {
    label: '知识库',
    title: '添加知识库',
    desc: '关联企业知识库，为智能体提供可信的信息来源（可独立配置调用与检索策略）。',
    emptyTitle: '暂无添加知识库',
    emptyDesc: '添加知识库后，智能体可在回答前检索企业私有资料。',
    addText: '添加知识库',
    searchPlaceholder: '搜索知识库名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.KNOWLEDGE
  },
  mcp: {
    label: 'MCP 服务',
    title: '添加 MCP 服务',
    desc: '关联 MCP 服务，让智能体通过标准协议调用外部能力。',
    emptyTitle: '暂无添加 MCP 服务',
    emptyDesc: '添加 MCP 服务后，可进一步选择暴露给智能体的工具范围。',
    addText: '添加 MCP 服务',
    searchPlaceholder: '搜索 MCP 名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.MCP
  },
  subAgent: {
    label: '子智能体',
    title: '添加子智能体',
    desc: '关联协作智能体，构建多智能体协同工作流。',
    emptyTitle: '暂无添加子智能体',
    emptyDesc: '添加后，当前智能体可把子任务委派给这些智能体。',
    addText: '添加子智能体',
    searchPlaceholder: '搜索智能体名称 / 描述',
    moreText: '继续添加',
    configRoute: RouteNames.AGENT
  }
}

/** 各分组的空态图标 */
const resourceIcons: Record<KnowledgeResourceKey, unknown> = {
  knowledge: BookOutlined,
  mcp: ApiOutlined,
  subAgent: ApartmentOutlined
}

/** 已选知识库（保持选择顺序） */
const selectedKnowledgeBases = computed(() => {
  const map = new Map(allKnowledgeBases.value.map(item => [String(item.id), item]))
  return (formData.value.knowledgeBase || []).map(id => map.get(id)).filter((item): item is KnowledgeBaseConfigVO => !!item)
})

/** 已选 MCP 服务（保持选择顺序） */
const selectedMcpServers = computed(() => {
  const map = new Map(allMcpServers.value.map(item => [String(item.id), item]))
  return (formData.value.mcp || []).map(id => map.get(id)).filter((item): item is McpServerVO => !!item)
})

/** 已选子智能体 */
const selectedSubAgents = computed(() => {
  const map = new Map(allAgents.value.map(item => [String(item.id), item]))
  return (formData.value.subAgent || []).map(id => map.get(id)).filter((item): item is AgentDefinitionVO => !!item)
})

/** 各分组已选数量 */
const selectedCount = computed<Record<KnowledgeResourceKey, number>>(() => ({
  knowledge: selectedKnowledgeBases.value.length,
  mcp: selectedMcpServers.value.length,
  subAgent: selectedSubAgents.value.length
}))

/** 各分组的候选项（弹窗用；MCP 不可运行时标记为禁选并给出原因） */
const pickerItems = computed<Record<KnowledgeResourceKey, AgentPickerItem[]>>(() => ({
  knowledge: allKnowledgeBases.value.map(kb => ({
    id: String(kb.id),
    name: kb.name,
    desc: kb.description,
    badge: kb.kbType
  })),
  mcp: allMcpServers.value.map(mcp => ({
    id: String(mcp.id),
    name: mcp.name,
    desc: mcp.description,
    badge: mcp.protocol,
    disabled: !isMcpRuntimeAvailable(mcp) && !isMcpSelected(String(mcp.id)),
    disabledReason: getMcpUnavailableReason(mcp)
  })),
  subAgent: availableAgents.value.map(agent => ({
    id: String(agent.id),
    name: agent.name,
    desc: agent.description,
    badge: agent.agentType === 'CUSTOM' ? '自定义' : 'A2A'
  }))
}))

/** 各分组当前已选 id */
const pickerSelected = computed<Record<KnowledgeResourceKey, string[]>>(() => ({
  knowledge: formData.value.knowledgeBase || [],
  mcp: formData.value.mcp || [],
  subAgent: formData.value.subAgent || []
}))

/**
 * 打开资源选择弹窗
 * @param key 资源分组
 */
function openPicker(key: KnowledgeResourceKey) {
  activePicker.value = key
}

/**
 * 弹窗确认：整表回写选中集（知识库沿用「首选取默认检索参数 / 清空则复位」的既有口径）
 * @param key 资源分组
 * @param ids 确认后的 id 列表
 */
function handlePickerConfirm(key: KnowledgeResourceKey, ids: string[]) {
  const currentModel = formData.value
  if (key === 'knowledge') {
    const current = currentModel.knowledgeBase || []
    const added = ids.find(id => !current.includes(id))
    let ragConfig = currentModel.ragConfig
    if (ids.length === 0) {
      ragConfig = null
    } else if (added && isEmptyRagConfig()) {
      const kb = allKnowledgeBases.value.find(item => String(item.id) === added)
      if (kb) {
        ragConfig = { ...extractRagDefaults(kb) }
      }
    }
    formData.value = { ...currentModel, knowledgeBase: [...ids], ragConfig }
    return
  }

  if (key === 'mcp') {
    const existing = new Map((currentModel.mcpBindings || []).map(item => [String(item.mcpServerId), item]))
    // ID 与绑定必须在一次更新中回写；emit 后 props 仍可能指向旧模型。
    const mcpBindings = ids.map(id => existing.get(id) ?? {
      mcpServerId: id,
      exposureMode: McpToolExposureMode.ALL_GLOBAL,
      mcpToolIds: []
    })
    formData.value = { ...currentModel, mcp: [...ids], mcpBindings }
    return
  }

  formData.value = { ...currentModel, subAgent: [...ids] }
}

/**
 * 移除单个已选项
 * @param key 资源分组
 * @param id 资源 id
 */
function removeResource(key: KnowledgeResourceKey, id: string) {
  handlePickerConfirm(key, (pickerSelected.value[key] || []).filter(item => item !== id))
}

/** 打开某个 MCP 的工具暴露配置 */
function openMcpConfig(mcpId: string) {
  configMcpId.value = mcpId
  void loadMcpTools(mcpId)
}

/** 关闭 MCP 工具暴露配置 */
function closeMcpConfig() {
  configMcpId.value = null
}

/** 当前正在配置的 MCP（弹窗标题用） */
const configMcp = computed(() =>
  allMcpServers.value.find(item => String(item.id) === configMcpId.value)
)

/**
 * 可选的子智能体列表
 */
const availableAgents = computed(() => {
  return allAgents.value.filter(a => String(a.id) !== String(props.currentAgentId))
})

/**
 * 已选 MCP ID 集合
 */
const selectedMcpIds = computed(() => {
  return new Set((formData.value.mcpBindings || []).map(item => String(item.mcpServerId)))
})

/**
 * 暴露模式选项
 */
const exposureModeOptions = [
  { label: '继承全局', value: McpToolExposureMode.ALL_GLOBAL },
  { label: '局部选择', value: McpToolExposureMode.SELECTED_ONLY }
]

function ensureMcpBindings() {
  if (!Array.isArray(formData.value.mcpBindings)) {
    formData.value.mcpBindings = []
  }
  if (!Array.isArray(formData.value.mcp)) {
    formData.value.mcp = []
  }

  if (formData.value.mcpBindings.length === 0 && formData.value.mcp.length > 0) {
    formData.value.mcpBindings = [...new Set(formData.value.mcp)].map(id => ({
      mcpServerId: id,
      exposureMode: McpToolExposureMode.ALL_GLOBAL,
      mcpToolIds: []
    }))
  }

  syncLegacyMcpIds()
}

function syncLegacyMcpIds() {
  formData.value.mcp = (formData.value.mcpBindings || []).map(item => String(item.mcpServerId))
}

function getBinding(mcpId: string): AgentMcpBindingVO | undefined {
  return (formData.value.mcpBindings || []).find(item => String(item.mcpServerId) === mcpId)
}

function isMcpSelected(mcpId: string) {
  return selectedMcpIds.value.has(mcpId)
}

function isMcpRuntimeAvailable(mcp: McpServerVO) {
  return Boolean(mcp.enabled)
    && mcp.activationStatus === McpActivationStatus.ACTIVE
    && (mcp.availableToolCount || 0) > 0
}

function getMcpTools(mcpId: string) {
  return mcpToolMap.value[mcpId] || []
}

function isToolLoading(mcpId: string) {
  return Boolean(toolLoadingMap.value[mcpId])
}

function isToolAvailable(tool: McpToolVO) {
  return Boolean(tool.enabled) && !tool.missing
}

function isToolSelected(mcpId: string, toolId: string) {
  return getBinding(mcpId)?.mcpToolIds?.includes(toolId) || false
}

function isToolSelectable(mcpId: string, tool: McpToolVO) {
  return isToolAvailable(tool) || isToolSelected(mcpId, String(tool.id))
}

async function loadMcpTools(mcpId: string) {
  if (mcpToolMap.value[mcpId]) {
    return
  }
  toolLoadingMap.value[mcpId] = true
  try {
    const response = await mcpApi.listTools(mcpId)
    mcpToolMap.value[mcpId] = response.data.data || []
  } finally {
    toolLoadingMap.value[mcpId] = false
  }
}

async function preloadSelectedMcpTools() {
  const targets = (formData.value.mcpBindings || [])
    .filter(item => item.exposureMode === McpToolExposureMode.SELECTED_ONLY)
    .map(item => String(item.mcpServerId))
  await Promise.all(targets.map(id => loadMcpTools(id)))
}

/**
 * 加载所有知识库
 */
async function loadAllKnowledgeBases() {
  const response = await knowledgeApi.page({ page: 1, size: 1000, enabled: true })
  allKnowledgeBases.value = (response.data.data.records || []).filter(item => item.enabled)
}

/**
 * 加载所有 MCP 服务
 */
async function loadAllMcpServers() {
  const response = await mcpApi.page({ page: 1, size: 1000 })
  allMcpServers.value = response.data.data.records || []
}

/**
 * 加载所有智能体
 */
async function loadAllAgents() {
  const response = await agentApi.page({ page: 1, size: 1000, enabled: true })
  allAgents.value = response.data.data.records || []
}

/**
 * 验证表单
 */
async function validate(): Promise<boolean> {
  if (activeGroups.value.includes('knowledge')) {
    const error = knowledgeStrategyError(formData.value.ragConfig)
    if (error) {
      knowledgeStrategyRef.value?.openSettings()
      message.warning(error)
      return false
    }
  }
  try {
    await formRef.value?.validate()
    return true
  } catch {
    return false
  }
}

/**
 * 编排概览：当前已选的知识库 / MCP 服务 / 子智能体名称
 * （供编排页锚点条显示数量，避免重复请求）
 */
function getSummary(): StudioSummary {
  const all: StudioSummary = {
    knowledge: { label: '知识库', names: selectedKnowledgeBases.value.map(item => item.name) },
    mcp: { label: 'MCP 服务', names: selectedMcpServers.value.map(item => item.name) },
    subAgent: { label: '子智能体', names: selectedSubAgents.value.map(item => item.name) }
  }
  // 只上报本次实际渲染的分组
  return Object.fromEntries(
    Object.entries(all).filter(([key]) => activeGroups.value.includes(key as KnowledgeResourceKey))
  )
}

/**
 * ragConfig 表单值（Top K / 阈值 / RAG 模式）
 */
interface RagConfigValue {
  topK: number
  scoreThreshold: number
  ragMode: RAGMode
}

const ragConfigForm = computed<RagConfigValue>(() => {
  const rc = formData.value.ragConfig
  const defaults = firstSelectedKnowledge.value
    ? extractRagDefaults(firstSelectedKnowledge.value)
    : { topK: 5, scoreThreshold: 0.5, ragMode: RAGMode.AGENTIC }
  return {
    topK: typeof rc?.topK === 'number' ? rc.topK : defaults.topK,
    scoreThreshold: typeof rc?.scoreThreshold === 'number' ? rc.scoreThreshold : defaults.scoreThreshold,
    ragMode: rc?.ragMode === RAGMode.GENERIC || rc?.ragMode === RAGMode.AGENTIC
      ? rc.ragMode
      : defaults.ragMode
  }
})

/**
 * 是否已选中知识库（选中后展示 ragConfig 配置区）
 */
const hasKnowledge = computed(() => (formData.value.knowledgeBase || []).length > 0)

/**
 * 首个选中的知识库（用于阈值文案与默认值来源）
 */
const firstSelectedKnowledge = computed(() => {
  const ids = formData.value.knowledgeBase || []
  return allKnowledgeBases.value.find(kb => String(kb.id) === ids[0])
})

/**
 * ragConfig 是否为空（未配置）
 */
function isEmptyRagConfig(): boolean {
  const rc = formData.value.ragConfig
  return !rc || Object.keys(rc).length === 0
}

/**
 * 从知识库配置中提取 RAG 默认参数（按类型映射阈值键）：
 * - topK：读取检索配置的 topK（百炼无此字段时用默认 5）
 * - 阈值：本地/百炼/Dify 读 scoreThreshold，RagFlow 读 similarityThreshold，缺失时用默认 0.5
 * - ragMode：取知识库自身的 ragMode
 */
function extractRagDefaults(kb: KnowledgeBaseConfigVO): RagConfigValue {
  const rc = kb.retrievalConfig || {}
  let scoreThreshold = kb.kbType === 'LIGHTRAG' ? 0 : 0.5
  if (kb.kbType === 'RAGFLOW') {
    if (typeof rc.similarityThreshold === 'number') scoreThreshold = rc.similarityThreshold
  } else if (typeof rc.scoreThreshold === 'number') {
    scoreThreshold = rc.scoreThreshold
  }
  return {
    topK: typeof rc.topK === 'number' ? rc.topK : kb.kbType === 'LIGHTRAG' ? 20 : 5,
    scoreThreshold,
    ragMode: kb.ragMode || RAGMode.AGENTIC
  }
}

/**
 * 仅在用户修改检索参数时回写，避免加载/清空操作把默认值写回模型。
 */
function updateRagConfig(value: Partial<RagConfigValue>) {
  if (!hasKnowledge.value) return
  formData.value = {
    ...formData.value,
    ragConfig: { ...ragConfigForm.value, ...formData.value.ragConfig, ...value }
  }
}

function updateKnowledgeStrategy(value: KnowledgeStrategy | null) {
  formData.value = { ...formData.value, ragConfig: value }
}

async function handleExposureModeChange(mcpId: string, exposureMode: McpToolExposureMode) {
  const binding = getBinding(mcpId)
  if (!binding) {
    return
  }
  binding.exposureMode = exposureMode
  if (exposureMode === McpToolExposureMode.SELECTED_ONLY) {
    await loadMcpTools(mcpId)
  }
}

function handleMcpToolChange(mcpId: string, toolId: string, checked: boolean) {
  const binding = getBinding(mcpId)
  if (!binding) {
    return
  }
  if (!Array.isArray(binding.mcpToolIds)) {
    binding.mcpToolIds = []
  }

  if (checked) {
    if (!binding.mcpToolIds.includes(toolId)) {
      binding.mcpToolIds.push(toolId)
    }
  } else {
    binding.mcpToolIds = binding.mcpToolIds.filter(id => id !== toolId)
  }
}

onMounted(async () => {
  ensureMcpBindings()
  const loaders: Record<KnowledgeResourceKey, () => Promise<void>> = {
    knowledge: loadAllKnowledgeBases,
    mcp: loadAllMcpServers,
    subAgent: loadAllAgents
  }
  loading.value = true
  try {
    // 工作台拆成多张卡后，每个实例只加载自身资源，避免重复请求触发网关限流。
    await Promise.all(activeGroups.value.map(key => loaders[key]()))
    if (activeGroups.value.includes('mcp')) await preloadSelectedMcpTools()
    // 候选列表就绪后才能把 id 还原为名称，通知编排页刷新概览。
    emit('ready')
  } finally {
    loading.value = false
  }
})

defineExpose({
  validate,
  getSummary
})
</script>

<template>
  <AppSpin :spinning="loading">
    <AForm ref="formRef" :model="formData" layout="vertical">
      <!-- 知识库：空态 / 已选 pill + 添加（青云式） -->
      <AFormItem v-if="activeGroups.includes('knowledge')" :label="`${pickerMeta.knowledge.label}（已选 ${selectedCount.knowledge}）`">
        <template v-if="selectedKnowledgeBases.length > 0">
          <div class="agstudio-res-pills">
            <div class="agstudio-res-pills__list">
              <span
                v-for="kb in selectedKnowledgeBases"
                :key="kb.id"
                class="agstudio-res-pill"
                :title="kb.name"
              >
                <span class="agstudio-res-pill__text">{{ kb.name }}</span>
                <em>{{ kb.kbType }}</em>
                <button type="button" title="移除" @click.stop="removeResource('knowledge', String(kb.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>
            <button type="button" class="agstudio-res-add-more" @click="openPicker('knowledge')">
              <PlusOutlined />继续添加
            </button>
          </div>
        </template>

        <div v-else class="agstudio-res-empty">
          <span class="agstudio-res-empty__icon"><component :is="resourceIcons.knowledge" /></span>
          <div class="agstudio-res-empty__copy">
            <strong>{{ pickerMeta.knowledge.emptyTitle }}</strong>
            <p>{{ pickerMeta.knowledge.emptyDesc }}</p>
          </div>
          <div class="agstudio-res-empty__actions">
            <button type="button" class="agstudio-res-empty__add" @click="openPicker('knowledge')">
              <PlusOutlined />{{ pickerMeta.knowledge.addText }}
            </button>
            <a
              class="agstudio-res-empty__link"
              :href="router.resolve({ name: pickerMeta.knowledge.configRoute }).href"
              target="_blank"
            >去配置</a>
          </div>
        </div>
      </AFormItem>

      <!-- MCP 服务：空态 / 已选 pill（每个 pill 可单独配置工具暴露）+ 添加 -->
      <AFormItem v-if="activeGroups.includes('mcp')" :label="`${pickerMeta.mcp.label}（已选 ${selectedCount.mcp}）`">
        <template v-if="selectedMcpServers.length > 0">
          <div class="agstudio-res-pills">
            <div class="agstudio-res-pills__list">
              <span
                v-for="mcp in selectedMcpServers"
                :key="mcp.id"
                class="agstudio-res-pill"
                :class="{ 'is-warn': !isMcpRuntimeAvailable(mcp) }"
                :title="mcp.name"
              >
                <span class="agstudio-res-pill__text">{{ mcp.name }}</span>
                <em>{{ getMcpConnectionStatusText(mcp) }}</em>
                <button
                  type="button"
                  class="is-config"
                  title="配置工具暴露范围"
                  @click.stop="openMcpConfig(String(mcp.id))"
                >
                  <SettingOutlined />
                </button>
                <button type="button" title="移除" @click.stop="removeResource('mcp', String(mcp.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>
            <button type="button" class="agstudio-res-add-more" @click="openPicker('mcp')">
              <PlusOutlined />继续添加
            </button>
          </div>
          <div class="text-placeholder text-xs mt-sm">
            点 pill 上的齿轮可配置该 MCP 的「工具暴露」范围（继承全局 / 局部选择）。
          </div>
        </template>

        <div v-else class="agstudio-res-empty">
          <span class="agstudio-res-empty__icon"><component :is="resourceIcons.mcp" /></span>
          <div class="agstudio-res-empty__copy">
            <strong>{{ pickerMeta.mcp.emptyTitle }}</strong>
            <p>{{ pickerMeta.mcp.emptyDesc }}</p>
          </div>
          <div class="agstudio-res-empty__actions">
            <button type="button" class="agstudio-res-empty__add" @click="openPicker('mcp')">
              <PlusOutlined />{{ pickerMeta.mcp.addText }}
            </button>
            <a
              class="agstudio-res-empty__link"
              :href="router.resolve({ name: pickerMeta.mcp.configRoute }).href"
              target="_blank"
            >去配置</a>
          </div>
        </div>
      </AFormItem>

      <!-- 子智能体 -->
      <AFormItem v-if="activeGroups.includes('subAgent')" :label="`${pickerMeta.subAgent.label}（已选 ${selectedCount.subAgent}）`">
        <template v-if="selectedSubAgents.length > 0">
          <div class="agstudio-res-pills">
            <div class="agstudio-res-pills__list">
              <span
                v-for="agent in selectedSubAgents"
                :key="agent.id"
                class="agstudio-res-pill"
                :title="agent.name"
              >
                <span class="agstudio-res-pill__text">{{ agent.name }}</span>
                <em>{{ agent.agentType === 'CUSTOM' ? '自定义' : 'A2A' }}</em>
                <button type="button" title="移除" @click.stop="removeResource('subAgent', String(agent.id))">
                  <CloseOutlined />
                </button>
              </span>
            </div>
            <button type="button" class="agstudio-res-add-more" @click="openPicker('subAgent')">
              <PlusOutlined />继续添加
            </button>
          </div>
          <div class="text-placeholder text-xs mt-sm">
            选中的智能体将作为当前智能体的可调用子智能体。
          </div>
        </template>

        <div v-else class="agstudio-res-empty">
          <span class="agstudio-res-empty__icon"><component :is="resourceIcons.subAgent" /></span>
          <div class="agstudio-res-empty__copy">
            <strong>{{ pickerMeta.subAgent.emptyTitle }}</strong>
            <p>{{ pickerMeta.subAgent.emptyDesc }}</p>
          </div>
          <div class="agstudio-res-empty__actions">
            <button type="button" class="agstudio-res-empty__add" @click="openPicker('subAgent')">
              <PlusOutlined />{{ pickerMeta.subAgent.addText }}
            </button>
            <a
              class="agstudio-res-empty__link"
              :href="router.resolve({ name: pickerMeta.subAgent.configRoute }).href"
              target="_blank"
            >去添加</a>
          </div>
        </div>
      </AFormItem>

      <!-- 当前智能体独立的调用、检索和证据策略 -->
      <AgentKnowledgeStrategy
        v-if="showAdvanced && hasKnowledge"
        ref="knowledgeStrategyRef"
        :model-value="formData.ragConfig"
        :knowledge-bases="selectedKnowledgeBases"
        @update:model-value="updateKnowledgeStrategy"
      />
    </AForm>
  </AppSpin>

  <!-- 资源选择弹窗 -->
  <AgentResourcePicker
    v-if="activePicker"
    :open="true"
    :title="pickerMeta[activePicker].title"
    :description="pickerMeta[activePicker].desc"
    :items="pickerItems[activePicker]"
    :selected-ids="pickerSelected[activePicker]"
    :search-placeholder="pickerMeta[activePicker].searchPlaceholder"
    :empty-text="pickerMeta[activePicker].emptyTitle"
    @update:open="(value: boolean) => { if (!value) activePicker = null }"
    @confirm="(ids: string[]) => handlePickerConfirm(activePicker as KnowledgeResourceKey, ids)"
  />

  <!-- 单个 MCP 的工具暴露配置 -->
  <a-modal
    :open="!!configMcpId"
    :title="`配置 MCP：${configMcp?.name || ''}`"
    :width="760"
    :footer="null"
    :destroy-on-close="true"
    @cancel="closeMcpConfig"
  >
    <div v-if="configMcp && configMcpId" class="mcp-config">
      <div class="mcp-tag-group">
        <ATag color="default" :bordered="false">{{ configMcp.protocol }}</ATag>
        <ATag :bordered="false" :color="getMcpConnectionStatusColor(configMcp)">
          {{ getMcpConnectionStatusText(configMcp) }}
        </ATag>
        <ATag :bordered="false" color="processing">全局可用 {{ configMcp.availableToolCount || 0 }}</ATag>
        <ATag v-if="!isMcpRuntimeAvailable(configMcp)" :bordered="false" color="warning">
          {{ getMcpUnavailableReason(configMcp) }}
        </ATag>
      </div>

      <div class="binding-row">
        <span class="binding-label">工具暴露</span>
        <ASegmented
          :value="getBinding(configMcpId)?.exposureMode || McpToolExposureMode.ALL_GLOBAL"
          :options="exposureModeOptions"
          size="small"
          @change="(value: McpToolExposureMode) => handleExposureModeChange(configMcpId as string, value)"
        />
      </div>

      <div v-if="!isMcpRuntimeAvailable(configMcp)" class="binding-tip warning">
        当前绑定会保留用于展示，但运行时不会注册该 MCP 的工具。
      </div>

      <template v-if="getBinding(configMcpId)?.exposureMode === McpToolExposureMode.SELECTED_ONLY">
        <div class="binding-tip">仅注册下方勾选、且当前仍全局可用的工具。</div>
        <AppSpin :spinning="isToolLoading(configMcpId)">
          <AButton
            v-if="!mcpToolMap[configMcpId]"
            type="link"
            class="tool-load-action"
            @click="loadMcpTools(configMcpId as string)"
          >
            加载工具目录
          </AButton>
          <AEmpty v-else-if="!getMcpTools(configMcpId).length" description="暂无工具目录" />
          <div v-else class="tool-grid">
            <label
              v-for="tool in getMcpTools(configMcpId)"
              :key="tool.id"
              class="tool-item"
              :class="{ unavailable: !isToolAvailable(tool) }"
            >
              <ACheckbox
                :checked="isToolSelected(configMcpId as string, String(tool.id))"
                :disabled="!isToolSelectable(configMcpId as string, tool)"
                @change="(e: any) => handleMcpToolChange(configMcpId as string, String(tool.id), e.target.checked)"
              />
              <div class="item-info">
                <div class="item-name" :title="tool.toolName">{{ tool.toolName }}</div>
                <div class="item-desc text-placeholder text-xs" :title="tool.description">
                  {{ tool.description || '暂无描述' }}
                </div>
              </div>
            </label>
          </div>
        </AppSpin>
      </template>

      <!-- 工具是否「需要人工确认」属于 MCP 工具治理，不在智能体编排页维护，只给入口 -->
      <div v-if="getMcpTools(configMcpId).length" class="confirm-entry">
        <span>
          单个工具是否需要人工确认，请在「MCP → 工具治理」里维护（运行时由确认 Hook 在调用前暂停等用户允许/拒绝）。
        </span>
        <AButton size="small" type="link" @click="goMcpGovernance">去 MCP 工具治理</AButton>
      </div>

      <div class="mcp-config__footer">
        <AButton type="primary" @click="closeMcpConfig">完成</AButton>
      </div>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-resource.scss' as *;

/* 已选 pill 上的「配置」按钮（MCP 工具暴露范围） */
.agstudio-res-pill button.is-config {
  color: #6f7fd8;
}

.agstudio-res-pill button.is-config:hover {
  color: #1677ff;
  background: #fff;
}

/* 运行时不可用的 MCP pill 用橙色提示，但保持可移除 */
.agstudio-res-pill.is-warn {
  color: #ad6800;
  background: #fff7e6;
}

.agstudio-res-pill.is-warn em {
  background: rgba(173, 104, 0, 0.12);
}

/* ===================== MCP 工具暴露配置弹窗 ===================== */
.mcp-config {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mcp-config .mcp-tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.mcp-config .binding-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mcp-config .binding-label {
  font-weight: 500;
}

.mcp-config .binding-tip {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.mcp-config .binding-tip.warning {
  color: #d48806;
}

.mcp-config .tool-load-action {
  padding-left: 0;
}

.mcp-config .tool-grid {
  display: grid;
  max-height: 380px;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
  overflow-y: auto;
}

.mcp-config .tool-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  cursor: pointer;
  transition: all var(--transition-base);
}

.mcp-config .tool-item:hover {
  border-color: var(--color-primary);
  background-color: var(--color-bg-light);
}

.mcp-config .tool-item.unavailable {
  background: rgba(15, 23, 42, 0.02);
  cursor: not-allowed;
}

.mcp-config .item-name {
  margin-bottom: 3px;
  color: #2b3445;
  font-weight: 500;
  font-size: 13px;
}

.mcp-config .item-desc {
  line-height: 1.4;
}

.mcp-config__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid #f0f4fa;
}

/* 工具确认的治理入口（确认开关本身在 MCP 工具治理页） */
.mcp-config .confirm-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
  padding-top: 12px;
  color: #8a97b0;
  border-top: 1px dashed #eef2f9;
  font-size: 12px;
  line-height: 1.6;
}
</style>
