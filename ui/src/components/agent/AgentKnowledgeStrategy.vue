<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { ApartmentOutlined, CheckOutlined, DownOutlined, LinkOutlined, SettingOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { KbType, RAGMode, type KnowledgeBaseConfigVO } from '@/types'
import { LIGHTRAG_DEFAULTS, LIGHTRAG_MODES } from '@/constants/lightrag'
import { KNOWLEDGE_STRATEGY_DEFAULTS, knowledgeStrategyError, recommendedKnowledgeStrategy, type AgentKnowledgeStrategy } from '@/utils/agentKnowledge'

const props = defineProps<{ modelValue: AgentKnowledgeStrategy | null; knowledgeBases: KnowledgeBaseConfigVO[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: AgentKnowledgeStrategy | null] }>()
const panelId = `knowledge-strategy-${useId()}`
const expanded = ref(false)
const advancedKeys = ref<string[]>([])
const defaults = KNOWLEDGE_STRATEGY_DEFAULTS
const value = computed(() => ({
  ...props.modelValue,
  maxCallsPerRound: props.modelValue?.maxCallsPerRound ?? defaults.maxCallsPerRound,
  timeoutSeconds: props.modelValue?.timeoutSeconds ?? defaults.timeoutSeconds,
  concurrency: props.modelValue?.concurrency ?? defaults.concurrency,
  maxContextChars: props.modelValue?.maxContextChars ?? defaults.maxContextChars,
  maxChunkChars: props.modelValue?.maxChunkChars ?? defaults.maxChunkChars,
  includeImages: props.modelValue?.includeImages ?? defaults.includeImages,
  maxImages: props.modelValue?.maxImages ?? defaults.maxImages,
  historyTurns: props.modelValue?.historyTurns ?? defaults.historyTurns,
  strictEvidence: props.modelValue?.strictEvidence ?? defaults.strictEvidence,
}))
const light = computed(() => props.modelValue?.lightRag ?? {})
const lightBases = computed(() => props.knowledgeBases.filter(kb => kb.kbType === KbType.LIGHTRAG))
const onlyLight = computed(() => !!lightBases.value.length && lightBases.value.length === props.knowledgeBases.length)
const currentMode = computed(() => props.modelValue?.ragMode ?? props.knowledgeBases[0]?.ragMode ?? RAGMode.AGENTIC)
const modeLabels: Record<string, string> = { naive: '向量检索', mix: '图谱 + 向量', local: '实体检索', global: '关系检索', hybrid: '实体与关系' }
const modes = computed(() => [...new Set(lightBases.value.map(kb => String(light.value.mode ?? inherited(kb, 'mode'))))])
const usesGraph = computed(() => modes.value.some(mode => mode !== 'naive'))
const lightModeSummary = computed(() => modes.value.length === 1 ? `${modeLabels[modes.value[0]!] ?? modes.value[0]} · ${modes.value[0]}` : '按知识库分别继承')
const error = computed(() => knowledgeStrategyError(props.modelValue))

// 根据完整配置判断预设，手动调整后立即显示「自定义」，不将预设选择另存为状态。
function matchesPreset(graph: boolean) {
  const preset = recommendedKnowledgeStrategy(graph)
  const config = props.modelValue
  if (!config || Object.keys(config).length !== Object.keys(preset).length) return false
  return Object.entries(preset).every(([key, expected]) => key === 'lightRag'
    ? Object.keys(config.lightRag ?? {}).length === Object.keys(preset.lightRag!).length
      && Object.entries(preset.lightRag!).every(([field, next]) => config.lightRag?.[field as keyof NonNullable<AgentKnowledgeStrategy['lightRag']>] === next)
    : config[key] === expected)
}
const presetKind = computed(() => !props.modelValue || !Object.keys(props.modelValue).length ? 'inherit'
  : matchesPreset(false) ? 'fast' : matchesPreset(true) ? 'graph' : 'custom')
const presetLabel = computed(() => ({ inherit: '继承默认', fast: '速度优先', graph: '图谱增强', custom: '自定义' })[presetKind.value])
const topKSummary = computed(() => value.value.topK == null ? '继承知识库' : `每库 ${value.value.topK} 段`)
const retrySummary = computed(() => value.value.maxRetries == null ? '重试继承' : `重试 ${value.value.maxRetries} 次`)
const tokenSummary = computed(() => light.value.maxTotalTokens == null ? 'Token 预算继承' : `${light.value.maxTotalTokens.toLocaleString()} Token`)

function patch(patch: Partial<AgentKnowledgeStrategy>) {
  emit('update:modelValue', { ...props.modelValue, ragMode: currentMode.value, ...patch })
}
function patchLight(key: string, next: unknown) {
  patch({ lightRag: { ...light.value, [key]: next === undefined || next === '' ? null : next } })
}
function number(key: keyof AgentKnowledgeStrategy, next: number | string | null) {
  patch({ [key]: next == null ? null : Number(next) })
}
function applyPreset(kind: 'fast' | 'graph' | 'inherit') {
  emit('update:modelValue', kind === 'inherit' ? null : recommendedKnowledgeStrategy(kind === 'graph'))
}
function openSettings() {
  expanded.value = true
  if (error.value) advancedKeys.value = ['retrieval', 'light', 'evidence']
}
function inherited(kb: KnowledgeBaseConfigVO, key: keyof typeof LIGHTRAG_DEFAULTS) {
  return kb.retrievalConfig?.[key] ?? LIGHTRAG_DEFAULTS[key]
}
function inheritedTopK(kb: KnowledgeBaseConfigVO) {
  return kb.retrievalConfig?.topK ?? (kb.kbType === KbType.LIGHTRAG ? LIGHTRAG_DEFAULTS.topK : 5)
}
const graphFields = [
  { key: 'graphTopK', label: '图谱检索数量', max: 1000, tip: '检索实体与关系的数量，按知识库分别执行。' },
  { key: 'maxEntityTokens', label: '实体 Token 上限', max: 1000000, tip: '知识图谱实体上下文的预算。' },
  { key: 'maxRelationTokens', label: '关系 Token 上限', max: 1000000, tip: '知识图谱关系上下文的预算。' },
] as const

defineExpose({ openSettings })
</script>

<template>
  <section class="knowledge-strategy" aria-label="知识库调用策略">
    <div class="strategy-overview">
      <div class="strategy-heading">
        <span class="strategy-heading__icon"><SettingOutlined /></span>
        <strong>知识库调用策略</strong>
        <span class="strategy-badge" :class="{ 'is-preset': presetKind === 'fast' || presetKind === 'graph' }">{{ presetLabel }}</span>
      </div>
      <button type="button" class="strategy-toggle" :aria-expanded="expanded" :aria-controls="panelId" @click="expanded ? expanded = false : openSettings()">
        {{ expanded ? '收起配置' : '配置策略' }}<DownOutlined :class="{ 'is-expanded': expanded }" />
      </button>
      <div class="strategy-summary">
        <span>{{ currentMode === 'GENERIC' ? '每轮自动检索一次' : `按需检索 · 每轮最多 ${value.maxCallsPerRound} 次` }}</span>
        <span v-if="lightBases.length">{{ lightModeSummary }}</span>
        <span>Top K · {{ topKSummary }}</span>
        <span>{{ value.timeoutSeconds }} 秒超时</span>
        <span>{{ value.includeImages ? `最多 ${value.maxImages} 张图片` : '仅文字证据' }}</span>
        <span>{{ value.strictEvidence ? '严格依据证据' : '允许补充通用知识' }}</span>
      </div>
    </div>

    <div v-if="expanded" :id="panelId" class="strategy-editor">
      <AAlert v-if="error" type="error" show-icon :message="error" class="strategy-error" />
      <div class="strategy-section-heading"><strong>选择预设</strong><span>填入当前草稿，保存智能体后生效</span></div>
      <div class="strategy-presets" :class="{ 'has-graph': lightBases.length }" role="group" aria-label="策略预设">
        <button type="button" class="strategy-preset" :class="{ 'is-selected': presetKind === 'fast' }" :aria-pressed="presetKind === 'fast'" @click="applyPreset('fast')">
          <span class="strategy-preset__title"><ThunderboltOutlined />速度优先<em>推荐</em><CheckOutlined v-if="presetKind === 'fast'" class="strategy-preset__check" /></span>
          <span>自动一次 · 20 秒 · 每库 6 段</span>
          <small>{{ lightBases.length ? 'naive 向量 · 关闭重排' : '严格依据证据 · 最多 2 张图片' }}</small>
        </button>
        <button v-if="lightBases.length" type="button" class="strategy-preset" :class="{ 'is-selected': presetKind === 'graph' }" :aria-pressed="presetKind === 'graph'" @click="applyPreset('graph')">
          <span class="strategy-preset__title"><ApartmentOutlined />图谱增强<CheckOutlined v-if="presetKind === 'graph'" class="strategy-preset__check" /></span>
          <span>图谱 + 向量 · 30 秒 · 每库 6 段</span>
          <small>mix 模式 · 适合实体关系问题</small>
        </button>
        <button type="button" class="strategy-preset" :class="{ 'is-selected': presetKind === 'inherit' }" :aria-pressed="presetKind === 'inherit'" @click="applyPreset('inherit')">
          <span class="strategy-preset__title"><LinkOutlined />继承默认<CheckOutlined v-if="presetKind === 'inherit'" class="strategy-preset__check" /></span>
          <span>使用各知识库的检索参数</span>
          <small>清除当前智能体的策略覆盖</small>
        </button>
      </div>

      <div class="strategy-section-heading"><strong>常用设置</strong><span>仅对当前智能体生效</span></div>
      <div class="strategy-fields">
        <AFormItem label="知识库调用方式" tooltip="自动检索在每轮首次推理前执行一次，省去模型决定调用的一次推理；按需检索由智能体决定是否查库。" class="strategy-fields__full">
          <ARadioGroup class="strategy-call-mode" :value="currentMode" aria-label="知识库调用方式" @update:value="patch({ ragMode: $event })">
            <ARadioButton value="GENERIC">每轮自动检索一次</ARadioButton>
            <ARadioButton value="AGENTIC">智能体按需检索</ARadioButton>
          </ARadioGroup>
        </AFormItem>
        <AFormItem v-if="lightBases.length" label="知识图谱检索模式" tooltip="naive 向量；local 实体；global 关系；hybrid 实体与关系；mix 图谱与向量。清空后继承各知识库。">
          <ASelect :value="light.mode ?? undefined" aria-label="知识图谱检索模式" allow-clear :placeholder="`继承 · ${lightModeSummary}`" :options="LIGHTRAG_MODES" @update:value="patchLight('mode', $event)" />
        </AFormItem>
        <AFormItem label="返回片段数（Top K）" tooltip="按知识库分别执行，图谱上下文另计。留空继承各知识库。">
          <AInputNumber :value="value.topK" aria-label="返回片段数（Top K）" placeholder="继承知识库" :min="1" :max="1000" :precision="0" @update:value="number('topK', $event)" />
        </AFormItem>
      </div>
      <p v-if="lightBases.length" class="strategy-mode-hint" :class="{ 'uses-graph': usesGraph }">
        {{ usesGraph ? '图谱模式可能调用关键词模型，通常比向量检索更慢。' : '向量模式适合低延迟问答；关系类问题可尝试 local 或 mix。' }}
      </p>
      <div class="strategy-switches">
        <div class="strategy-switch-row">
          <div><strong>严格依据证据回答</strong><p>保留来源，证据不足时明确说明</p></div>
          <ASwitch :checked="value.strictEvidence" aria-label="严格依据证据回答" @update:checked="patch({ strictEvidence: $event })" />
        </div>
        <div class="strategy-switch-row">
          <div><strong>返回相关图片</strong><p>{{ value.includeImages ? `每次最多 ${value.maxImages} 张，可在下方调整额度` : '关闭后减少图片传输与视觉处理开销' }}</p></div>
          <ASwitch :checked="value.includeImages" aria-label="返回相关图片" @update:checked="patch({ includeImages: $event })" />
        </div>
      </div>

      <div class="strategy-section-heading strategy-section-heading--advanced"><strong>高级参数</strong><span>按需展开调整</span></div>
      <ACollapse v-model:activeKey="advancedKeys" class="strategy-advanced" :bordered="false" expand-icon-position="end">
        <ACollapsePanel key="retrieval">
          <template #header><div class="strategy-group-heading"><strong>调用与等待预算</strong><span>{{ value.timeoutSeconds }} 秒超时 · 并发 {{ value.concurrency }} 库<template v-if="lightBases.length"> · {{ retrySummary }}</template></span></div></template>
          <div class="strategy-fields">
            <AFormItem label="单次检索总超时（秒）" tooltip="包括所有知识库的排队、请求和重试；超时后结束等待并向模型说明失败。">
              <AInputNumber :value="value.timeoutSeconds" aria-label="单次检索总超时（秒）" :min="1" :max="180" :precision="0" @update:value="number('timeoutSeconds', $event)" />
            </AFormItem>
            <AFormItem label="同时检索的知识库数" tooltip="多个知识库并行可减少等待，也会提高后端瞬时负载。">
              <AInputNumber :value="value.concurrency" aria-label="同时检索的知识库数" :min="1" :max="4" :precision="0" @update:value="number('concurrency', $event)" />
            </AFormItem>
            <AFormItem v-if="currentMode === 'AGENTIC'" label="每轮最多检索次数" tooltip="包含失败尝试，达到上限后停止补查。">
              <AInputNumber :value="value.maxCallsPerRound" aria-label="每轮最多检索次数" :min="1" :max="8" :precision="0" @update:value="number('maxCallsPerRound', $event)" />
            </AFormItem>
            <AFormItem v-else label="携带历史问题轮数" tooltip="仅拼接此前用户问题，不额外调用模型改写。指代较多的追问建议 1 轮。">
              <AInputNumber :value="value.historyTurns" aria-label="携带历史问题轮数" :min="0" :max="4" :precision="0" @update:value="number('historyTurns', $event)" />
            </AFormItem>
            <AFormItem v-if="lightBases.length" label="知识图谱最大重试次数" tooltip="留空继承知识库。低延迟场景建议 0，重试仍受总超时约束。">
              <AInputNumber :value="value.maxRetries" aria-label="知识图谱最大重试次数" placeholder="继承知识库" :min="0" :max="3" :precision="0" @update:value="number('maxRetries', $event)" />
            </AFormItem>
            <AFormItem v-if="!onlyLight" label="分数 / 相似度阈值" tooltip="仅对支持统一相关度的知识库生效，知识图谱不使用该阈值。留空继承各知识库。">
              <AInputNumber :value="value.scoreThreshold" aria-label="分数 / 相似度阈值" placeholder="继承知识库" :min="0" :max="1" :step="0.05" @update:value="number('scoreThreshold', $event)" />
            </AFormItem>
          </div>
        </ACollapsePanel>

        <ACollapsePanel v-if="lightBases.length" key="light">
          <template #header><div class="strategy-group-heading"><strong>知识图谱高级检索</strong><span>{{ tokenSummary }} · {{ light.enableRerank == null ? '重排继承' : light.enableRerank ? '重排开启' : '重排关闭' }}</span></div></template>
          <div class="strategy-fields">
            <AFormItem label="总上下文 Token 上限" tooltip="知识图谱服务的证据预算，向量模式也生效；与平台的证据字符上限同时约束结果。">
              <AInputNumber :value="light.maxTotalTokens" aria-label="总上下文 Token 上限" placeholder="继承知识库" :min="1" :max="1000000" :precision="0" @update:value="patchLight('maxTotalTokens', $event == null ? null : Number($event))" />
            </AFormItem>
            <AFormItem label="结果重排" tooltip="开启需要知识图谱服务配置重排模型，可能增加额外请求。">
              <ASelect :value="light.enableRerank == null ? 'inherit' : light.enableRerank ? 'on' : 'off'" aria-label="结果重排" @update:value="patchLight('enableRerank', $event === 'inherit' ? null : $event === 'on')">
                <ASelectOption value="inherit">继承知识库</ASelectOption><ASelectOption value="off">关闭 · 速度优先</ASelectOption><ASelectOption value="on">开启</ASelectOption>
              </ASelect>
            </AFormItem>
            <template v-if="usesGraph">
              <AFormItem v-for="field in graphFields" :key="field.key" :label="field.label" :tooltip="field.tip">
                <AInputNumber :value="light[field.key]" :aria-label="field.label" placeholder="继承知识库" :min="1" :max="field.max" :precision="0" @update:value="patchLight(field.key, $event == null ? null : Number($event))" />
              </AFormItem>
            </template>
          </div>
          <p class="strategy-help">留空继承各知识库。{{ usesGraph ? '图谱参数按知识库分别生效。' : '切换到图谱模式后可调整图谱数量及实体、关系预算。' }}</p>
        </ACollapsePanel>

        <ACollapsePanel key="evidence">
          <template #header><div class="strategy-group-heading"><strong>证据与图片额度</strong><span>{{ value.maxContextChars.toLocaleString() }} 字符<template v-if="value.includeImages"> · 最多 {{ value.maxImages }} 张图片</template><template v-else> · 图片关闭</template></span></div></template>
          <div class="strategy-fields">
            <AFormItem label="总证据字符上限" tooltip="多个知识库合计，去重后按顺序注入；超出预算会截断并标记，来源始终保留。">
              <AInputNumber :value="value.maxContextChars" aria-label="总证据字符上限" :min="2000" :max="120000" :step="1000" :precision="0" @update:value="number('maxContextChars', $event)" />
            </AFormItem>
            <AFormItem label="单个片段字符上限" tooltip="也约束图谱上下文，不能超过总证据上限。">
              <AInputNumber :value="value.maxChunkChars" aria-label="单个片段字符上限" :min="500" :max="20000" :step="500" :precision="0" @update:value="number('maxChunkChars', $event)" />
            </AFormItem>
            <AFormItem v-if="value.includeImages" label="最多返回图片数" tooltip="每次检索多个知识库合计，可设 1–20 张。实际返回取决于命中图片和文件大小；速度优先建议 2 张。">
              <AInputNumber :value="value.maxImages" aria-label="最多返回图片数" :min="1" :max="20" :precision="0" @update:value="number('maxImages', $event)" />
            </AFormItem>
          </div>
          <p class="strategy-help">控制证据量有助于减少模型输入开销。严格依据证据是回答约束，资料本身仍需审核。</p>
        </ACollapsePanel>
      </ACollapse>

      <details class="strategy-inherited">
        <summary>查看知识库默认值（{{ knowledgeBases.length }}）</summary>
        <div v-for="kb in knowledgeBases" :key="kb.id" class="strategy-inherited__item">
          <strong>{{ kb.name }}<em>{{ kb.kbType }}</em></strong>
          <div>
            <span>Top K {{ inheritedTopK(kb) }}</span>
            <template v-if="kb.kbType === KbType.LIGHTRAG">
              <span>{{ inherited(kb, 'mode') }}</span><span>图谱数量 {{ inherited(kb, 'graphTopK') }}</span>
              <span>实体 {{ inherited(kb, 'maxEntityTokens') }} Token</span><span>关系 {{ inherited(kb, 'maxRelationTokens') }} Token</span>
              <span>总量 {{ inherited(kb, 'maxTotalTokens') }} Token</span><span>重排{{ inherited(kb, 'enableRerank') ? '开启' : '关闭' }}</span>
              <span>重试 {{ kb.httpConfig?.maxRetries ?? 0 }} 次</span>
            </template>
            <span v-else>阈值 {{ (kb.kbType === KbType.RAGFLOW ? kb.retrievalConfig?.similarityThreshold : kb.retrievalConfig?.scoreThreshold) ?? 0.5 }}</span>
          </div>
        </div>
      </details>
      <p class="strategy-footnote">检索参数留空时继承知识库；等待与证据预算使用平台默认值。修改后保存智能体，后续运行生效。</p>
    </div>
  </section>
</template>

<style scoped>
.knowledge-strategy { container-type: inline-size; color: #354055; border: 1px solid #e8edf6; border-radius: 10px; background: #fff; }
.strategy-overview { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 16px; background: #fbfcff; border-radius: 10px; }
.strategy-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; font-size: 13px; }
.strategy-heading__icon { display: grid; place-items: center; color: #5268e9; }
.strategy-badge { padding: 2px 7px; border-radius: 5px; background: #eef1f6; color: #758198; font-size: 11px; font-weight: 400; }
.strategy-badge.is-preset { color: #5268e9; background: #eef1ff; }
.strategy-toggle { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 4px 0 4px 8px; border: 0; background: transparent; color: #5268e9; font-size: 12px; cursor: pointer; }
.strategy-toggle .anticon { font-size: 10px; transition: transform .2s; }
.strategy-toggle .is-expanded { transform: rotate(180deg); }
.strategy-summary { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 6px 0; color: #758198; font-size: 12px; line-height: 1.5; }
.strategy-summary > span { display: inline-flex; align-items: center; }
.strategy-summary > span:not(:last-child)::after { content: ''; width: 3px; height: 3px; margin: 0 9px; border-radius: 50%; background: #c7cfdd; }
.strategy-editor { padding: 18px 16px 14px; border-top: 1px solid #e8edf6; }
.strategy-error { margin-bottom: 16px; }
.strategy-section-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 12px; margin-bottom: 12px; font-size: 13px; }
.strategy-section-heading > span { color: #8a97ac; font-size: 11px; }
.strategy-presets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-bottom: 24px; }
.strategy-presets.has-graph { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.strategy-preset { display: flex; flex-direction: column; gap: 5px; min-width: 0; padding: 12px; border: 1px solid #e4eaf4; border-radius: 8px; background: #fff; color: #758198; text-align: left; font-size: 11px; line-height: 1.6; cursor: pointer; transition: border-color .15s, background .15s; }
.strategy-preset:hover { border-color: #9eaef5; background: #fafbff; }
.strategy-preset.is-selected { border-color: #778af1; background: #f4f6ff; }
.strategy-preset__title { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: #354055; font-size: 13px; font-weight: 600; }
.strategy-preset__title > .anticon { color: #6c80e8; }
.strategy-preset__title > em { padding: 0 4px; border-radius: 3px; color: #5268e9; background: #e9eeff; font-size: 10px; font-style: normal; font-weight: 400; }
.strategy-preset__check { margin-left: auto; }
.strategy-preset > small { color: #8a97ac; font-size: 11px; }
.strategy-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.strategy-fields__full { grid-column: 1 / -1; }
.knowledge-strategy :deep(.ant-form-item) { min-width: 0; margin-bottom: 16px; }
.knowledge-strategy :deep(.ant-form-item-label) { padding-bottom: 6px; }
.knowledge-strategy :deep(.ant-form-item-label > label) { height: auto; color: #526078; font-size: 12px; }
.knowledge-strategy :deep(.ant-input-number), .knowledge-strategy :deep(.ant-select) { width: 100%; }
.strategy-call-mode { display: inline-flex; max-width: 100%; }
.strategy-call-mode :deep(.ant-radio-button-wrapper) { height: auto; padding: 7px 15px; line-height: 1.5; text-align: center; font-size: 12px; }
.strategy-mode-hint { margin: -3px 0 16px; color: #758198; font-size: 12px; line-height: 1.6; }
.strategy-mode-hint.uses-graph { color: #a16b27; }
.strategy-switches { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding: 14px 0 0; border-top: 1px solid #f0f2f7; }
.strategy-switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.strategy-switch-row strong { font-size: 12px; font-weight: 500; }
.strategy-switch-row p { margin: 4px 0 0; color: #8a97ac; font-size: 11px; line-height: 1.6; }
.strategy-switch-row :deep(.ant-switch) { flex-shrink: 0; }
.strategy-section-heading--advanced { margin-top: 22px; }
.strategy-advanced { overflow: hidden; background: #fafbfe; border: 1px solid #edf0f6; border-radius: 8px; }
.strategy-advanced :deep(.ant-collapse-item) { border-bottom-color: #edf0f6; }
.strategy-advanced :deep(.ant-collapse-item:last-child) { border-bottom: 0; }
.strategy-advanced :deep(.ant-collapse-header) { align-items: center !important; padding: 12px 14px !important; }
.strategy-advanced :deep(.ant-collapse-content-box) { padding: 16px 14px 4px !important; background: #fff; border-top: 1px solid #edf0f6; }
.strategy-group-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; }
.strategy-group-heading > strong { font-size: 12px; font-weight: 500; }
.strategy-group-heading > span { color: #8a97ac; font-size: 11px; line-height: 1.6; }
.strategy-help, .strategy-footnote { color: #8a97ac; font-size: 11px; line-height: 1.7; }
.strategy-help { margin: 0 0 12px; }
.strategy-inherited { margin-top: 16px; color: #758198; font-size: 12px; }
.strategy-inherited > summary { width: fit-content; cursor: pointer; }
.strategy-inherited__item { padding: 12px 0; border-bottom: 1px solid #f0f2f7; }
.strategy-inherited__item > strong { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; overflow-wrap: anywhere; font-size: 12px; font-weight: 500; }
.strategy-inherited__item em { color: #8a97ac; font-size: 10px; font-style: normal; font-weight: 400; }
.strategy-inherited__item > div { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 7px; color: #8a97ac; font-size: 11px; }
.strategy-footnote { margin: 14px 0 0; }
.knowledge-strategy button:focus-visible, .strategy-inherited > summary:focus-visible { outline: 2px solid #5268e9; outline-offset: 3px; }
@container (max-width: 580px) {
  .strategy-presets.has-graph { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .strategy-presets.has-graph > :last-child { grid-column: 1 / -1; flex-direction: row; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 12px; padding: 9px 12px; }
  .strategy-presets.has-graph > :last-child > span:not(.strategy-preset__title) { display: none; }
  .strategy-switches { grid-template-columns: 1fr; gap: 14px; }
}
@container (max-width: 380px) {
  .strategy-overview { padding: 12px; gap: 10px; }
  .strategy-editor { padding: 16px 12px 12px; }
  .strategy-heading { gap: 6px; }
  .strategy-heading__icon { display: none; }
  .strategy-presets, .strategy-presets.has-graph, .strategy-fields { grid-template-columns: minmax(0, 1fr); }
  .strategy-call-mode { display: flex; }
  .strategy-call-mode :deep(.ant-radio-button-wrapper) { flex: 1; padding: 8px; }
  .strategy-group-heading { flex-direction: column; }
}
</style>
