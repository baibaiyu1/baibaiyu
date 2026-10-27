<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 顶栏「选模型」下拉
 *
 * 对齐参照页 `AgentOrchestrationEdit` 顶栏的模型选择器：折叠态显示「模型名 + 提供商」，
 * 展开后按提供商分组列出可选模型，选中即写回 `formData.model.modelConfigId`。
 *
 * @component
 */
import { computed, onMounted, ref } from 'vue'
import { CheckOutlined, DownOutlined } from '@ant-design/icons-vue'
import * as modelApi from '@/api/model'
import type { ModelConfigVO, ModelProviderVO } from '@/types'

const props = defineProps<{
  /** 当前选中的模型配置 id */
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const open = ref(false)
const loading = ref(false)
const models = ref<ModelConfigVO[]>([])
const providers = ref<ModelProviderVO[]>([])

/** 提供商 id → 名称 */
const providerNames = computed(() =>
  Object.fromEntries(providers.value.map(item => [String(item.id), item.name]))
)

/** 按提供商分组的模型 */
const groups = computed(() => {
  const map = new Map<string, ModelConfigVO[]>()
  for (const model of models.value) {
    const key = String(model.providerId || '其他')
    if (!map.has(key)) map.set(key, [])
    map.get(key)?.push(model)
  }
  return [...map.entries()].map(([providerId, items]) => ({
    providerId,
    providerName: providerNames.value[providerId] || '其他提供商',
    items
  }))
})

/** 当前模型 */
const current = computed(() => models.value.find(item => String(item.id) === String(props.modelValue)))

/** 折叠态主文案 */
const currentLabel = computed(() => current.value?.name || current.value?.modelId || '请选择模型')

/**
 * 折叠态副文案：`提供商 · 模型标识`（参照页形如 `deepseek-v4-flash（deepseek-v4-flash）`）
 */
const currentSubLabel = computed(() => {
  if (!current.value) return ''
  const provider = providerNames.value[String(current.value.providerId)] || ''
  const modelId = current.value.modelId || ''
  const parts = [provider, modelId].filter(Boolean)
  return parts.length ? `（${parts.join(' · ')}）` : ''
})

/**
 * 加载模型与提供商（两个接口并发，任一失败不影响另一个）
 */
async function load() {
  loading.value = true
  try {
    const [modelRes, providerRes] = await Promise.all([
      modelApi.conversationConfigPage({ page: 1, size: 1000, enabled: true } as never),
      modelApi.providerPage({ page: 1, size: 200 } as never)
    ])
    models.value = modelRes.data.data.records || []
    providers.value = providerRes.data.data.records || []
  } catch (e) {
    console.error('加载模型列表失败:', e)
  } finally {
    loading.value = false
  }
}

/**
 * 选中模型
 * @param model 模型配置
 */
function select(model: ModelConfigVO) {
  emit('update:modelValue', String(model.id))
  open.value = false
}

onMounted(load)

defineExpose({ load })
</script>

<template>
  <a-dropdown v-model:open="open" :trigger="['click']" placement="bottomRight">
    <button type="button" class="agstudio-model" :class="{ 'is-open': open }">
      <span class="agstudio-model__copy">
        <strong>{{ currentLabel }}<small v-if="currentSubLabel" class="agstudio-model__sub">{{ currentSubLabel }}</small></strong>
      </span>
      <DownOutlined class="agstudio-model__caret" />
    </button>

    <template #overlay>
      <div class="agstudio-model-menu">
        <div class="agstudio-model-menu__head">
          <span>选择模型</span>
          <em v-if="loading">加载中…</em>
          <em v-else>共 {{ models.length }} 个可用</em>
        </div>

        <div class="agstudio-model-menu__body">
          <template v-if="groups.length">
            <div v-for="group in groups" :key="group.providerId" class="agstudio-model-menu__group">
              <div class="agstudio-model-menu__group-name">{{ group.providerName }}</div>
              <button
                v-for="model in group.items"
                :key="model.id"
                type="button"
                class="agstudio-model-menu__item"
                :class="{ 'is-active': String(model.id) === String(modelValue) }"
                @click="select(model)"
              >
                <span class="agstudio-model-menu__item-copy">
                  <strong>{{ model.name || model.modelId }}</strong>
                  <small>{{ model.modelId }}</small>
                </span>
                <CheckOutlined v-if="String(model.id) === String(modelValue)" />
              </button>
            </div>
          </template>
          <div v-else class="agstudio-model-menu__empty">
            {{ loading ? '正在加载模型…' : '暂无可用模型，请先在「模型」页配置' }}
          </div>
        </div>
      </div>
    </template>
  </a-dropdown>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-model.scss' as *;
</style>
