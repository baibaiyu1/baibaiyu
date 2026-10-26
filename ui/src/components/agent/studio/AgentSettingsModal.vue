<script setup lang="ts">
/**
 * 智能体编排工作台 —— 「Agent 设置」弹窗
 *
 * 对齐参照页 `AgentSettingsModal`：卡片式大弹窗（86vh × 1100px），
 * 头部标题「Agent 设置」+ 关闭按钮，底部「取消 / 保存设置」；
 * 正文左侧是竖排 timeline 导航（基础信息 / 安全策略 / 高级配置，含 hint 说明），
 * 右侧是滚动内容区，滚动时自动高亮当前分区、点导航平滑滚动。
 *
 * 子表单直接复用既有的 `AgentFormBasic`（名称/编号/标签/描述）、
 * `AgentFormTools` 的钩子与敏感词（安全策略）、`AgentFormAdvanced`（高级配置），
 * 因此字段与校验口径与原来的 5 步表单完全一致。
 *
 * @component
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { cloneDeep } from 'lodash-es'
import { CloseOutlined, InfoCircleOutlined, SafetyCertificateOutlined, SettingOutlined } from '@ant-design/icons-vue'
import AgentFormBasic from '@/components/agent/AgentFormBasic.vue'
import AgentFormTools from '@/components/agent/AgentFormTools.vue'
import AgentFormAdvanced from '@/components/agent/AgentFormAdvanced.vue'
import AgentReasoningSettings from '@/components/agent/AgentReasoningSettings.vue'
import type { ModelConfigVO } from '@/types'

/** 分区标识 */
export type AgentSettingsSection = 'basic' | 'reasoning' | 'security' | 'advanced'
export type AgentSettingsDraft = {
  basic: Record<string, unknown>
  tools: Record<string, unknown>
  advanced: Record<string, unknown>
  modelParamsOverride?: Record<string, unknown> | null
}

const props = defineProps<{
  /** 是否打开 */
  open: boolean
  /** 基础信息表单模型 */
  basic: Record<string, unknown>
  /** 安全策略（钩子 / 敏感词）表单模型 */
  tools: Record<string, unknown>
  /** 高级配置表单模型 */
  advanced: Record<string, unknown>
  /** 标签候选 */
  tags: string[]
  /** 租户编码（智能体编号前缀） */
  tenantCode?: string
  /** 当前模型配置 id（高级配置里的记忆压缩顾问需要） */
  modelConfigId?: string
  model?: ModelConfigVO
  /** 模型参数覆盖（高级配置需要） */
  modelParamsOverride?: Record<string, unknown> | null
  /** 打开时定位到的分区 */
  initialSection?: AgentSettingsSection
  /** 保存中 */
  saving?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 保存设置（父级负责落库） */
  save: [value: AgentSettingsDraft]
}>()

const basicModel = ref<Record<string, unknown>>({})
const toolsModel = ref<Record<string, unknown>>({})
const advancedModel = ref<Record<string, unknown>>({})
const reasoningModel = ref<Record<string, unknown> | null>(null)
const reasoningFormRef = ref()

/** 分区定义（顺序即 timeline 顺序） */
const sections: Array<{ id: AgentSettingsSection; label: string; hint: string; icon: unknown }> = [
  { id: 'basic', label: '基础信息', hint: '名称、编码、标签与描述', icon: InfoCircleOutlined },
  { id: 'reasoning', label: '推理设置', hint: '继承或独立配置推理与展示', icon: SettingOutlined },
  { id: 'security', label: '安全策略', hint: 'Hook 与敏感词策略', icon: SafetyCertificateOutlined },
  { id: 'advanced', label: '高级配置', hint: '模型策略、记忆与对话展示', icon: SettingOutlined }
]

const basicFormRef = ref()
const securityFormRef = ref()
const advancedFormRef = ref()

/** 内容滚动容器 */
const contentRef = ref<HTMLElement | null>(null)
/** 各分区 DOM */
const sectionRefs = ref<Record<string, HTMLElement | null>>({})

/** 当前高亮分区 */
const activeSection = ref<AgentSettingsSection>('basic')
/** 程序触发的滚动期间忽略滚动联动，避免高亮跳动 */
let lockScroll = false
let lockTimer: ReturnType<typeof setTimeout> | null = null

function setSectionRef(id: AgentSettingsSection, el: unknown) {
  sectionRefs.value[id] = (el as HTMLElement | null) ?? null
}

/**
 * 点击导航：平滑滚动到分区
 * @param id 分区标识
 */
function scrollToSection(id: AgentSettingsSection) {
  const container = contentRef.value
  const target = sectionRefs.value[id]
  if (!container || !target) {
    activeSection.value = id
    return
  }
  activeSection.value = id
  lockScroll = true
  if (lockTimer) clearTimeout(lockTimer)
  lockTimer = setTimeout(() => {
    lockScroll = false
    lockTimer = null
  }, 500)
  const offset = target.getBoundingClientRect().top - container.getBoundingClientRect().top
  container.scrollTo({ top: Math.max(container.scrollTop + offset - 24, 0), behavior: 'smooth' })
}

/**
 * 滚动联动：取最后一个「越过阈值线」的分区
 */
function handleContentScroll() {
  if (lockScroll) return
  const container = contentRef.value
  if (!container) return
  const line = container.getBoundingClientRect().top + 120
  let current: AgentSettingsSection = 'basic'
  for (const section of sections) {
    const el = sectionRefs.value[section.id]
    if (el && el.getBoundingClientRect().top <= line) current = section.id
  }
  activeSection.value = current
}

/**
 * 校验三个子表单
 */
async function validateAll(): Promise<boolean> {
  const results = await Promise.all([
    basicFormRef.value?.validate?.() ?? Promise.resolve(true),
    securityFormRef.value?.validate?.() ?? Promise.resolve(true),
    advancedFormRef.value?.validate?.() ?? Promise.resolve(true),
    reasoningFormRef.value?.validate?.() ?? Promise.resolve(true)
  ])
  return results.every(item => item !== false)
}

/**
 * 保存设置（父级落库）
 */
async function handleSave() {
  if (!(await validateAll())) {
    message.warning('请先完善表单校验后再保存')
    return
  }
  emit('save', {
    basic: cloneDeep(basicModel.value),
    tools: cloneDeep(toolsModel.value),
    advanced: cloneDeep(advancedModel.value),
    modelParamsOverride: cloneDeep(reasoningModel.value)
  })
}

function close() {
  emit('update:open', false)
}

/** 打开后定位到 initialSection 或回到顶部 */
async function resetScroll() {
  await nextTick()
  const container = contentRef.value
  if (!container) return
  if (props.initialSection) {
    scrollToSection(props.initialSection)
    return
  }
  container.scrollTo({ top: 0 })
  activeSection.value = 'basic'
}

let openedBefore = false
watch(() => props.open, async (open) => {
  if (open) {
    basicModel.value = cloneDeep(props.basic)
    toolsModel.value = cloneDeep(props.tools)
    advancedModel.value = cloneDeep(props.advanced)
    reasoningModel.value = cloneDeep(props.modelParamsOverride || null)
    await resetScroll()
    if (openedBefore) {
      await securityFormRef.value?.reloadAll?.()
    }
    openedBefore = true
  }
})

onBeforeUnmount(() => {
  if (lockTimer) clearTimeout(lockTimer)
})
</script>

<template>
  <a-modal
    :open="open"
    :width="1100"
    :footer="null"
    :closable="false"
    :mask-closable="false"
    :destroy-on-close="false"
    wrap-class-name="agstudio-settings-modal"
    @cancel="close"
  >
    <div class="agstudio-settings">
      <header class="agstudio-settings__head">
        <div>
          <h2>Agent 设置</h2>
          <p>
            集中维护基础信息、安全策略和高级配置。
            <span>保存设置后，将会在发布后生效</span>
          </p>
        </div>
        <button type="button" class="agstudio-settings__close" title="关闭" @click="close">
          <CloseOutlined />
        </button>
      </header>

      <div class="agstudio-settings__body">
        <aside class="agstudio-settings__nav">
          <nav>
            <button
              v-for="(section, index) in sections"
              :key="section.id"
              type="button"
              class="agstudio-settings__nav-item"
              :class="{ 'is-active': activeSection === section.id }"
              @click="scrollToSection(section.id)"
            >
              <span class="agstudio-settings__nav-rail" />
              <span class="agstudio-settings__nav-index">{{ index + 1 }}</span>
              <span class="agstudio-settings__nav-copy">
                <span class="agstudio-settings__nav-label">
                  <component :is="section.icon" />
                  {{ section.label }}
                </span>
                <span class="agstudio-settings__nav-hint">{{ section.hint }}</span>
              </span>
            </button>
          </nav>
        </aside>

        <section ref="contentRef" class="agstudio-settings__content" @scroll="handleContentScroll">
          <div class="agstudio-settings__sections">
            <section :ref="(el: unknown) => setSectionRef('basic', el)" class="agstudio-settings__panel">
              <header class="agstudio-settings__panel-head">
                <h4>基础信息</h4>
                <p>维护智能体名称、编码、标签和描述，这些字段会参与列表展示与对外访问。</p>
              </header>
              <AgentFormBasic
                ref="basicFormRef"
                v-model="basicModel as never"
                :tags="tags"
                :tenant-code="tenantCode"
              />
            </section>

            <section :ref="(el: unknown) => setSectionRef('reasoning', el)" class="agstudio-settings__panel">
              <header class="agstudio-settings__panel-head">
                <h4>推理设置</h4>
                <p>为本智能体单独配置推理模式、强度和思考内容展示。</p>
              </header>
              <AgentReasoningSettings ref="reasoningFormRef" v-model="reasoningModel" :model="model" />
            </section>

            <section :ref="(el: unknown) => setSectionRef('security', el)" class="agstudio-settings__panel">
              <header class="agstudio-settings__panel-head">
                <h4>安全策略</h4>
                <p>为智能体挂载运行前后拦截能力，并绑定敏感词过滤方案。</p>
              </header>
              <AgentFormTools
                ref="securityFormRef"
                v-model="toolsModel as never"
                :groups="['hook', 'sensitive']"
                show-advanced
              />
            </section>

            <section :ref="(el: unknown) => setSectionRef('advanced', el)" class="agstudio-settings__panel">
              <header class="agstudio-settings__panel-head">
                <h4>高级配置</h4>
                <p>配置模型容错、摘要模型、任务清单、记忆和对话展示。</p>
              </header>
              <AgentFormAdvanced
                ref="advancedFormRef"
                v-model="advancedModel as never"
                :model-config-id="modelConfigId"
                :model-params-override="reasoningModel"
              />
            </section>
          </div>
        </section>
      </div>

      <footer class="agstudio-settings__footer">
        <a-button @click="close">取消</a-button>
        <a-button type="primary" :loading="saving" @click="handleSave">保存设置</a-button>
      </footer>
    </div>
  </a-modal>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-settings.scss' as *;
</style>
