/**
 * 智能体管理主页面
 *
 */
<script setup lang="ts">
/* eslint-disable vue/multi-word-component-names */
import { onMounted, ref, computed, h, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import {RobotOutlined, SearchOutlined} from '@ant-design/icons-vue'
import { useAgentStore } from '@/stores'
import { storeToRefs } from 'pinia'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'
import * as agentApi from '@/api/agent'
import * as agentA2aApi from '@/api/agentA2a'
import * as resourceApi from '@/api/resource'
import type { AgentDefinitionVO, WellKnownAgentConfig, NacosAgentConfig } from '@/types'
import AgentCard from '@/components/agent/AgentCard.vue'
import ResourceScopeTabs from '@/components/resource/ResourceScopeTabs.vue'
import ResourceOwnershipModal from '@/components/resource/ResourceOwnershipModal.vue'
import ResourceGrantModal from '@/components/resource/ResourceGrantModal.vue'
import CreateCard from '@/components/agent/CreateCard.vue'
import AgentForm from '@/components/agent/AgentForm.vue'
import {AppModalApi} from "@/components/common/AppModalApi.ts";
import AppInfiniteLoading from '@/components/common/AppInfiniteLoading.vue'
import { RouteNames } from '@/router/constants'

const router = useRouter()
const spaceStore = useResourceSpaceStore()
const store = useAgentStore()
const { list, tags, selectedAgentType, selectedTag, keyword, scope, loading, hasMore } = storeToRefs(store)

/** 当前账号收藏的智能体ID集合 */
const favoriteIds = ref<string[]>([])
const ownershipOpen = ref(false)
const ownershipId = ref<string | null>(null)
function openOwnership(id: string) { ownershipId.value = id; ownershipOpen.value = true }
const grantOpen = ref(false)
const grantResourceId = ref<string | null>(null)
const grantResourceName = computed(() =>
  list.value.find(item => String(item.id) === grantResourceId.value)?.name || ''
)

function openGrants(id: string) {
  grantResourceId.value = id
  grantOpen.value = true
}

const formVisible = ref<boolean>(false)
/** A2A 类型（新建时传入） */
const currentA2aType = ref<'WELLKNOWN' | 'NACOS' | undefined>(undefined)
/** 用于强制重建 InfiniteLoading 组件的 key */
const infiniteLoadingKey = ref(0)
/** 是否首次加载 */
const isFirstLoad = ref(true)

/**
 * 智能体类型选项
 */
const agentTypeOptions = [
  { label: '全部', value: null },
  { label: '自定义', value: 'CUSTOM' },
  { label: 'A2A', value: 'A2A' }
]

/**
 * 标签选项列表
 */
const tagOptions = computed(() => {
  const options = tags.value.map((tag: string) => ({
    label: tag,
    value: tag
  }))
  return [
    { label: '全部', value: null },
    ...options
  ]
})

function agentTagLabel(data: AgentDefinitionVO): string {
  return (data.tags?.length ? data.tags : data.tag ? [data.tag] : []).join('、')
}

/**
 * 新建自定义智能体
 */
function handleCreateCustom() {
  currentA2aType.value = undefined
  formVisible.value = true
}

/**
 * 新建 A2A 智能体
 *
 * @param type A2A 协议类型
 */
function handleCreateA2a(type: 'WELLKNOWN' | 'NACOS') {
  currentA2aType.value = type
  formVisible.value = true
}

/**
 * 处理查看 — 根据类型分居展示内容
 *
 * @param id 智能体 ID
 */
async function handleView(id: string) {
  const response = await agentApi.detail(id)
  const data = response.data.data

  if (data.agentType === 'A2A') {
    try {
      const a2aRes = await agentA2aApi.getA2aConfig(id)
      const a2aRecord = a2aRes.data.data
      const type = a2aRecord?.a2aType as string
      const config = a2aRecord?.a2aConfig

      let content
      if (type === 'WELLKNOWN' && config) {
        const c = config as WellKnownAgentConfig
        const headerRows = (c.authHeaders || []).map((item) =>
          h('p', { style: { paddingLeft: '16px' } }, [
            `${item.key}: `,
            item.evn
              ? h('span', { style: { color: '#888' } }, `环境变量 ${item.key}`)
              : item.value
          ])
        )
        content = h('div', { style: { maxHeight: '800px', overflowY: 'auto' } }, [
          h('p', {}, [h('strong', 'A2A 类型: '), 'WellKnown']),
          ...(agentTagLabel(data) ? [h('p', {}, [h('strong', '标签: '), agentTagLabel(data)])] : []),
          h('p', {}, [h('strong', '名称: '), data.name]),
          h('p', {}, [h('strong', '智能体编号: '), data.agentCode]),
          h('p', {}, [h('strong', '描述: '), data.description]),
          h('p', {}, [h('strong', '钩子数量: '), data.hook.length]),
          h('p', {}, [h('strong', '启用记忆: '), data.enableMemory ? '是' : '否']),
          h('p', {}, [h('strong', 'Base URL: '), c.baseUrl]),
          h('p', {}, [h('strong', 'Agent Card 路径: '), c.relativeCardPath]),
          h('p', {}, [h('strong', '认证头数量: '), (c.authHeaders || []).length]),
          ...headerRows
        ])
      } else if (type === 'NACOS' && config) {
        const c = config as NacosAgentConfig
        const propRows = (c.nacosProperties || []).map((p) =>
          h('p', { style: { paddingLeft: '16px' } }, [
            `${p.key}: `,
            p.evn
              ? h('span', { style: { color: '#888' } }, `环境变量 ${p.key}`)
              : p.value
          ])
        )
        content = h('div', { style: { maxHeight: '800px', overflowY: 'auto' } }, [
          h('p', {}, [h('strong', 'A2A 类型: '), 'Nacos']),
          ...(agentTagLabel(data) ? [h('p', {}, [h('strong', '标签: '), agentTagLabel(data)])] : []),
          h('p', {}, [h('strong', '名称: '), data.name]),
          h('p', {}, [h('strong', '智能体编号: '), data.agentCode]),
          h('p', {}, [h('strong', '描述: '), data.description]),
          h('p', {}, [h('strong', '钩子数量: '), data.hook.length]),
          h('p', {}, [h('strong', '启用记忆: '), data.enableMemory ? '是' : '否']),
          h('p', {}, [h('strong', 'Nacos 属性数量: '), (c.nacosProperties || []).length]),
          ...propRows
        ])
      } else {
        content = h('p', {}, 'A2A 配置暂无数据')
      }

      AppModalApi.open({
        title: 'A2A 智能体详情',
        titleIcon: null,
        footer: null,
        content
      })
    } catch (e) {
      console.error('加载 A2A 配置失败:', e)
    }
    return
  }

  AppModalApi.open({
    title: '智能体详情',
    titleIcon: RobotOutlined,
    footer: null,
    content: h('div', {}, [
      h('p', {}, [h('strong', '主智能体: '), data.used?.length ? data.used.join('、') : '无']),
      h('p', {}, [h('strong', '名称: '), data.name]),
      h('p', {}, [h('strong', '智能体编号: '), data.agentCode]),
      h('p', {}, [h('strong', '描述: '), data.description]),
      ...(agentTagLabel(data) ? [h('p', {}, [h('strong', '标签: '), agentTagLabel(data)])] : []),
      h('p', {}, [h('strong', '模型配置ID: '), data.modelConfigId]),
      h('p', {}, [h('strong', '系统提示词模板ID: '), data.systemPromptTemplateId]),
      h('p', {}, [h('strong', '随模板变化: '), data.followTemplate ? '是' : '否']),
      h('p', {}, [h('strong', '钩子数量: '), data.hook.length]),
      h('p', {}, [h('strong', '工具选择策略: '), data.toolChoiceStrategy]),
      h('p', {}, [h('strong', '工具数量: '), data.tool.length]),
      h('p', {}, [h('strong', '技能包数量: '), data.skill.length]),
      h('p', {}, [h('strong', '配置代码执行: '), data.codeExecutionConfigId ? '是' : '否']),
      h('p', {}, [h('strong', '知识库数量: '), data.knowledgeBase.length]),
      h('p', {}, [h('strong', 'MCP数量: '), data.mcp.length]),
      h('p', {}, [h('strong', '子智能体数量: '), data.subAgent.length]),
      h('p', {}, [h('strong', '启用敏感词: '), data.sensitiveFilterEnabled ? '是' : '否']),
      h('p', {}, [h('strong', '显示工具调用历史: '), data.showToolProcess ? '是' : '否']),
      h('p', {}, [h('strong', '启用计划: '), data.enablePlanning ? '是' : '否']),
      h('p', {}, [h('strong', '启用记忆: '), data.enableMemory ? '是' : '否']),
      h('p', {}, [h('strong', '结构化输出: '), data.structuredOutputEnabled ? '是' : '否']),
      h('p', {}, [h('strong', 'Studio 可视化调试: '), data.studioConfigId ? '是' : '否'])
    ])
  })
}

/**
 * 重置列表状态并重建 InfiniteLoading 组件
 */
function resetListAndRebuild() {
  list.value = [];
  store.resetPagination();
  isFirstLoad.value = true;
  infiniteLoadingKey.value++;
}

/**
 * 处理删除
 */
async function handleDelete(id: string) {
  const used = await store.checkUsedWithAgent(id)
  if (used.length > 0) {
    Modal.confirm({
      title: '二次确认',
      content: `该智能体正在被 [ ${used.join('、')} ] 智能体引用，删除后可能会影响上述智能体的正常使用！`,
      okText: '确认并继续删除',
      onOk: async () => {
        await store.deleteConfig(id)
        resetListAndRebuild()
      }
    })
    return
  }

  Modal.confirm({
    title: '确认删除',
    content: '删除后无法恢复,是否继续?',
    onOk: async () => {
      await store.deleteConfig(id)
      resetListAndRebuild()
    }
  })
}

/**
 * 处理表单提交成功
 */
function handleFormSuccess(id: string) {
  resetListAndRebuild()
  store.fetchTags()
  handleConfigPanel(id)
}

/**
 * 处理搜索
 */
function handleSearch() {
  store.setKeyword(keyword.value)
}

/**
 * 访问智能体对话（新开页）
 */
function handleGoVisit(id: string) {
  const hash = `#/chat/${encodeURIComponent(id)}`
  const url = `${window.location.origin}${window.location.pathname}${hash}`
  window.open(url, '_blank')
}

/**
 * 打开编排工作台（路由页）
 *
 * 由「青云Agent平台」形态的独立页面承载配置、定时、架构、API、日志、统计，
 * 支持分享链接、刷新保留与多标签并行编辑。
 *
 * @param id 智能体 ID
 */
function handleConfigPanel(id: string) {
  router.push({ name: RouteNames.AGENT_CONFIG, params: { id } })
}

/**
 * 处理状态
 */
async function handleEnable(id: string) {
  const response = await agentApi.detail(id)
  const { enabled } = response.data.data

  const used = await store.checkUsedWithAgent(id)
  if (used.length > 0 && enabled) {
    Modal.confirm({
      title: '二次确认',
      content: `该智能体正在被 [ ${used.join('、')} ] 智能体引用，禁用后可能会影响上述智能体的正常使用！`,
      okText: '确认并继续',
      onOk: async () => {
        await store.toggleEnabled(id, !enabled)
      }
    })
    return
  }

  await store.toggleEnabled(id, !enabled)
}

/**
 * 处理无限加载
 *
 * @param $state 加载状态对象
 */
async function handleInfiniteLoading($state: {
  loaded: () => void;
  complete: () => void;
  error: () => void;
}) {
  if (isFirstLoad.value) {
    isFirstLoad.value = false;
    if (list.value.length > 0) {
      $state.loaded();
      return;
    }
    try {
      await store.fetchPage(1);
      if (hasMore.value) {
        $state.loaded();
      } else {
        $state.complete();
      }
    } catch {
      // 失败时重置首次加载标记，确保重试走正确的首次加载路径
      isFirstLoad.value = true;
      $state.error();
    }
    return;
  }

  if (!hasMore.value || loading.value) {
    $state.complete();
    return;
  }

  try {
    await store.loadMore();
    if (hasMore.value) {
      $state.loaded();
    } else {
      $state.complete();
    }
  } catch {
    $state.error();
  }
}

/**
 * 加载当前账号的收藏ID集合
 */
async function loadFavoriteIds() {
  try {
    const response = await resourceApi.favoriteIds('AGENT')
    favoriteIds.value = response.data.data || []
  } catch (e) {
    console.error('加载收藏列表失败:', e)
  }
}

/**
 * 判断智能体是否已被当前账号收藏
 */
function isFavorited(id: unknown) {
  return favoriteIds.value.includes(String(id))
}

/**
 * 收藏 / 取消收藏
 */
async function handleFavorite(id: string) {
  const response = await resourceApi.toggleFavorite('AGENT', id)
  const favorited = response.data.data === true
  if (favorited) {
    if (!isFavorited(id)) favoriteIds.value = [...favoriteIds.value, id]
  } else {
    favoriteIds.value = favoriteIds.value.filter((item) => item !== id)
  }
  message.success(favorited ? '已收藏' : '已取消收藏')
  // 收藏视图下取消收藏，需要同步刷新列表
  if (scope.value === 'favorite') {
    resetListAndRebuild()
  }
}

/**
 * Fork 副本（副本默认仅自己可见且停用）
 */
function handleFork(id: string) {
  Modal.confirm({
    title: 'Fork 副本',
    content: '将复制一份智能体副本，副本默认「仅自己可见 + 停用」，确认继续？',
    okText: '创建副本',
    cancelText: '取消',
    onOk: async () => {
      const response = await resourceApi.forkAgent(id)
      message.success(`已创建副本「${response.data.data?.name || ''}」`)
      resetListAndRebuild()
      await loadFavoriteIds()
    }
  })
}

/**
 * 切换可见性（仅自己可见 / 组织公开）
 */
async function handleVisibility(payload: { id: string; visibility: 'PRIVATE' | 'TENANT' }) {
  await resourceApi.updateVisibility('AGENT', payload.id, payload.visibility)
  message.success(payload.visibility === 'PRIVATE' ? '已恢复默认访问范围' : '已扩大共享范围')
  resetListAndRebuild()
}

/**
 * 监听筛选条件变化，重置状态并重建 InfiniteLoading
 */
watch([selectedAgentType, selectedTag, keyword, scope, () => spaceStore.selectedSpace], () => {
  list.value = [];
  store.resetPagination();
  isFirstLoad.value = true;
  infiniteLoadingKey.value++;
});

onMounted(() => {
  store.fetchTags()
  void loadFavoriteIds()
})
</script>

<template>
  <div class="agent-page">
    <section class="intro-section">
      <h3 class="intro-title">智能体</h3>
      <p class="intro-desc text-secondary">
        智能体是AI系统的核心组件,通过配置模型、工具、提示词、敏感词、技能包、MCP和知识库等多个维度,
        赋予智能体强大的感知、推理和执行能力。精心设计的智能体能够理解复杂需求、制定执行计划、
        调用外部工具、访问知识库,并以结构化的方式输出结果,真正实现从"对话助手"到"智能伙伴"的跨越。
      </p>
    </section>

    <section class="filter-section flex justify-between items-center">
      <div class="filter-left">
        <ASegmented
          v-model:value="selectedAgentType"
          :options="agentTypeOptions"
        />
        <ResourceScopeTabs v-model:value="scope" />
      </div>

      <div class="filter-right flex items-center gap-md">
        <ASelect
          v-model:value="selectedTag"
          placeholder="选择标签"
          style="width: 200px; border-radius: 6px;"
        >
          <ASelectOption v-for="opt in tagOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ASelectOption>
        </ASelect>

        <AInput
          v-model:value="keyword"
          placeholder="搜索智能体名称"
          style="width: 300px; "
          @pressEnter="handleSearch"
        >
          <template #suffix>
            <AButton type="text" size="small" @click="handleSearch">
              <SearchOutlined />
            </AButton>
          </template>
        </AInput>
      </div>
    </section>

    <section class="card-section">
      <div class="card-grid">
        <CreateCard v-if="spaceStore.canCreate"
          :agent-type="selectedAgentType"
          v-permission="['agent:definition:add']"
          @create-custom="handleCreateCustom"
          @create-a2a="handleCreateA2a"
        />

        <AgentCard
          v-for="item in list"
          :key="item.id"
          :data="item"
          :favorited="isFavorited(item.id)"
          @view="handleView"
          @config-panel="handleConfigPanel"
          @enable="handleEnable"
          @delete="handleDelete"
          @go-visit="handleGoVisit"
          @favorite="handleFavorite"
          @fork="handleFork"
          @visibility="handleVisibility"
          @grants="openGrants"
          @ownership="openOwnership"
        />
      </div>

      <AppInfiniteLoading
        :loading-key="infiniteLoadingKey"
        @infinite="handleInfiniteLoading"
      />
    </section>

    <!-- 创建智能体，后续配置在设计页完成 -->
    <AgentForm
      v-model:visible="formVisible"
      :a2a-type="currentA2aType"
      :tags="tags"
      @success="handleFormSuccess"
    />

    <ResourceOwnershipModal v-model:open="ownershipOpen" resource-type="AGENT" :resource-id="ownershipId" @changed="store.resetAndFetch()" />
    <ResourceGrantModal
      v-model:open="grantOpen"
      resource-type="AGENT"
      :resource-id="grantResourceId"
      :resource-name="grantResourceName"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/agent/index.scss' as *;
</style>
