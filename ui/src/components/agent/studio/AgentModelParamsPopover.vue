<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 顶栏「模型参数」气泡
 *
 * 对齐参照页 `ModelParamsPopover` + `ModelParamsForm`：顶栏一个 40×40 图标按钮，
 * 点开是「模型参数覆盖」卡片——开关打开后可覆盖 Temperature / Top P / Top K /
 * Max Tokens / Repeat Penalty / Seed / 流式输出 / 扩展配置（headers/queryParams/bodyParams）。
 * 「开启覆盖」时按所选模型的默认参数预填，关闭时清空覆盖。
 *
 * 说明：本仓的模型配置接口不提供「单模型默认参数」端点，默认值取列表里的
 * `ModelConfigVO`（temperature/topP/topK/maxTokens/repeatPenalty/seed/streaming）。
 *
 * @component
 */
import { computed, ref } from 'vue'
import { SettingOutlined } from '@ant-design/icons-vue'
import ExtendConfigEditor, { type ExtendConfigData } from '@/components/model/ExtendConfigEditor.vue'
import type { AgentDefinitionVO, ModelConfigVO } from '@/types'
import { cleanReasoningExtensions, hasSamplingOverride, reasoningOnly } from '@/utils/modelReasoning'

const props = defineProps<{
  /** 智能体当前的表单模型（含 modelParamsOverride） */
  formData: AgentDefinitionVO | Record<string, unknown>
  /** 当前选中的模型配置（用于取默认值） */
  model?: ModelConfigVO
  /** 是否 OpenAI 系（决定是否显示「固定系统消息」开关） */
  providerType?: string
}>()

const emit = defineEmits<{
  /** 覆盖参数变化（null = 未开启覆盖） */
  'update:override': [value: Record<string, unknown> | null]
}>()

type OverrideValue = {
  temperature?: number
  topP?: number
  topK?: number
  contextWindow?: number
  maxTokens?: number
  repeatPenalty?: number
  seed?: string
  streaming?: boolean
  extendConfig?: ExtendConfigData | null
  reasoning?: unknown
}

const open = ref(false)
/** 是否开启覆盖 */
const enabled = ref(false)
/** 编辑中的参数 */
const draft = ref<OverrideValue>({})

/** 是否 OpenAI 系（extendConfig 的「固定系统消息」仅对该系显示） */
const isOpenAi = computed(() => (props.providerType || '').toUpperCase().includes('OPEN_AI'))

/** 用所选模型的默认参数预填 */
function fillFromModel() {
  const model = props.model
  draft.value = model
    ? {
        temperature: Number(model.temperature ?? 0.7),
        topP: Number(model.topP ?? 0.9),
        topK: Number(model.topK ?? 0),
        maxTokens: Number(model.maxTokens ?? 4096),
        repeatPenalty: Number(model.repeatPenalty ?? 1),
        seed: model.seed ? String(model.seed) : '',
        streaming: model.streaming !== false,
        extendConfig: cleanReasoningExtensions(model.extendConfig as ExtendConfigData | null)
      }
    : { streaming: true }
}

/** 打开气泡：按当前覆盖态初始化 */
function handleOpenChange(value: boolean, done?: () => void) {
  open.value = value
  if (value) {
    const current = getOverride()
    enabled.value = hasSamplingOverride(current as Record<string, unknown> | null)
    if (enabled.value && current) {
      draft.value = { ...current }
      delete draft.value.reasoning
    } else {
      enabled.value = false
      fillFromModel()
    }
  }
  done?.()
}

/** 读取智能体上的覆盖参数 */
function getOverride(): OverrideValue | null {
  const override = (props.formData as { modelParamsOverride?: OverrideValue | null }).modelParamsOverride
  return override && Object.keys(override).length > 0 ? override : null
}

/**
 * 切换「开启覆盖」：开启时用模型默认参数预填，关闭时清空
 * @param value 是否开启
 */
function handleToggle(value: boolean) {
  enabled.value = value
  if (value) {
    if (!draft.value || Object.keys(draft.value).length === 0) fillFromModel()
    emitChange()
    return
  }
  draft.value = {}
  emit('update:override', reasoningOnly(props.formData.modelParamsOverride as Record<string, unknown> | null))
}

/** 提交当前编辑值 */
function emitChange() {
  if (!enabled.value) {
    emit('update:override', reasoningOnly(props.formData.modelParamsOverride as Record<string, unknown> | null))
    return
  }
  const value = draft.value
  const next: Record<string, unknown> = Object.fromEntries(
    Object.entries(value).filter(([key, item]) => key !== 'reasoning' && item != null && (key !== 'seed' || !!item))
  )
  emit('update:override', { ...next, ...reasoningOnly(props.formData.modelParamsOverride as Record<string, unknown> | null) })
}

/** 是否有模型可选（没选模型时给出提示） */
const hasModel = computed(() => !!props.model)

function reset() {
  fillFromModel()
  emitChange()
}
</script>

<template>
  <a-popover
    :open="open"
    trigger="click"
    placement="bottomRight"
    :arrow="false"
    :overlay-style="{ padding: 0 }"
    @update:open="handleOpenChange"
  >
    <button
      type="button"
      class="agstudio-params__trigger"
      :class="{ 'is-on': !!getOverride() }"
      title="模型参数"
    >
      <SettingOutlined />
    </button>

    <template #content>
      <div class="agstudio-params">
        <header class="agstudio-params__head">
          <div>
            <h4>模型参数覆盖</h4>
            <p>开启后可覆盖所选模型的默认参数，未开启时使用模型自身配置。</p>
          </div>
          <a-switch :checked="enabled" size="small" @change="(v: any) => handleToggle(!!v)" />
        </header>

        <div v-if="enabled && hasModel" class="agstudio-params__body">
          <div class="agstudio-params__grid">
            <label class="agstudio-params__field">
              <span>Temperature</span>
              <a-input-number v-model:value="draft.temperature" :min="0" :max="2" :step="0.1" class="w-full" @change="emitChange" />
            </label>
            <label class="agstudio-params__field">
              <span>Top P</span>
              <a-input-number v-model:value="draft.topP" :min="0" :max="1" :step="0.1" class="w-full" @change="emitChange" />
            </label>
            <label class="agstudio-params__field">
              <span>Top K</span>
              <a-input-number v-model:value="draft.topK" :min="0" :max="100" class="w-full" @change="emitChange" />
            </label>
            <label class="agstudio-params__field">
              <span>Max Tokens</span>
              <a-input-number v-model:value="draft.maxTokens" :min="1" :max="1000000" class="w-full" @change="emitChange" />
            </label>
            <label class="agstudio-params__field">
              <span>Repeat Penalty</span>
              <a-input-number v-model:value="draft.repeatPenalty" :min="0" :max="2" :step="0.1" class="w-full" @change="emitChange" />
            </label>
            <label class="agstudio-params__field">
              <span>Seed</span>
              <a-input v-model:value="draft.seed" placeholder="随机种子（可空）" @change="emitChange" />
            </label>
          </div>

          <label class="agstudio-params__switch">
            <span>流式输出</span>
            <a-switch v-model:checked="draft.streaming" size="small" @change="emitChange" />
          </label>

          <div class="agstudio-params__extend">
            <ExtendConfigEditor
              :model-value="draft.extendConfig ?? null"
              compact
              :show-fixed-system-message="isOpenAi"
              @update:model-value="(value: ExtendConfigData | null) => { draft.extendConfig = value; emitChange() }"
            />
          </div>

          <div class="agstudio-params__footer">
            <a-button size="small" @click="reset">按模型默认值重置</a-button>
          </div>
        </div>

        <div v-else class="agstudio-params__empty">
          <span>{{ hasModel ? '开启覆盖后可调整模型参数' : '请先在上方选择一个模型' }}</span>
        </div>
      </div>
    </template>
  </a-popover>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-params.scss' as *;
</style>
