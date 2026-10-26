<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import AgentFormBasic from './AgentFormBasic.vue'
import type { AgentDefinitionVO } from '@/types'
import { A2aType } from '@/types'
import * as agentApi from '@/api/agent'

const props = defineProps<{
  visible: boolean
  tags: string[]
  a2aType?: 'WELLKNOWN' | 'NACOS'
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: [id: string]
}>()

const loading = ref(false)
const basicFormRef = ref<InstanceType<typeof AgentFormBasic>>()
const basicData = ref(emptyBasicData())
const title = computed(() => props.a2aType
  ? `创建 A2A 智能体 · ${props.a2aType === 'WELLKNOWN' ? 'WellKnown' : 'Nacos'}`
  : '创建智能体')

function emptyBasicData() {
  return { name: '', agentCode: '', description: '', tag: '', tags: [] as string[] }
}

watch(() => props.visible, visible => {
  if (visible) basicData.value = emptyBasicData()
})

async function handleSubmit() {
  if (loading.value) return
  loading.value = true
  try {
    if (!await basicFormRef.value?.validate()) return

    const vo: Partial<AgentDefinitionVO> = {
      name: basicData.value.name,
      description: basicData.value.description,
      tag: basicData.value.tag,
      tags: basicData.value.tags,
      agentType: props.a2aType ? 'A2A' : 'CUSTOM',
    }
    if (props.a2aType) {
      vo.agentA2A = {
        a2aType: props.a2aType === 'WELLKNOWN' ? A2aType.WELLKNOWN : A2aType.NACOS,
        a2aConfig: props.a2aType === 'WELLKNOWN'
          ? { agentName: '', baseUrl: '', relativeCardPath: '/.well-known/agent-card.json', authHeaders: [] }
          : { agentName: '', nacosProperties: [{ key: 'serverAddr', value: '', evn: false }] },
      }
    }

    const response = await agentApi.save(vo)
    const id = response.data.data.id
    message.success('创建成功')
    emit('update:visible', false)
    emit('success', String(id))
  } catch (error) {
    console.error('创建智能体失败:', error)
  } finally {
    loading.value = false
  }
}

function handleCancel() {
  if (!loading.value) emit('update:visible', false)
}
</script>

<template>
  <AModal
    :open="visible"
    :title="title"
    :width="560"
    :confirm-loading="loading"
    :closable="!loading"
    :mask-closable="!loading"
    :keyboard="!loading"
    :cancel-button-props="{ disabled: loading }"
    ok-text="创建并设计"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
    @cancel="handleCancel"
  >
    <p class="text-secondary mb-md">填写基本信息，创建后进入设计页继续配置。</p>
    <AgentFormBasic
      v-if="visible"
      ref="basicFormRef"
      v-model="basicData"
      :tags="tags"
      :show-agent-code="false"
    />
  </AModal>
</template>
