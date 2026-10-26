/**
 * 工具管理主页面
 *
 */
<script setup lang="ts">
/* eslint-disable vue/multi-word-component-names */
import { onMounted, ref, computed, h, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { changeResourceVisibility } from '@/utils/resourceVisibility'
import {SearchOutlined, ToolOutlined, ImportOutlined} from '@ant-design/icons-vue'
import { useToolStore } from '@/stores'
import { storeToRefs } from 'pinia'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'
import * as toolApi from '@/api/tool'
import type {ToolVO} from '@/types'
import ToolCard from '@/components/tool/ToolCard.vue'
import ResourceOwnershipModal from '@/components/resource/ResourceOwnershipModal.vue'
import ResourceGrantModal from '@/components/resource/ResourceGrantModal.vue'
import CreateCard from '@/components/tool/CreateCard.vue'
import ToolForm from '@/components/tool/ToolForm.vue'
import ResourceScopeTabs from '@/components/resource/ResourceScopeTabs.vue'
import OpenApiImportModal from '@/components/tool/OpenApiImportModal.vue'
import {AppModalApi} from "@/components/common/AppModalApi.ts";
import AppInfiniteLoading from '@/components/common/AppInfiniteLoading.vue'

const spaceStore = useResourceSpaceStore()
const store = useToolStore()
const { list, categories, selectedToolType, selectedCategory, keyword, scope, loading, hasMore } = storeToRefs(store)

const formVisible = ref<boolean>(false)
const currentData = ref<ToolVO | undefined>(undefined)
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
/** 用于强制重建 InfiniteLoading 组件的 key */
const infiniteLoadingKey = ref(0)
/** 是否首次加载 */
const isFirstLoad = ref(true)

/**
 * 工具类型选项
 */
const toolTypeOptions = [
  { label: '全部', value: null },
  { label: '内置', value: 'BUILTIN' },
  { label: '自定义', value: 'CUSTOM' }
]

/**
 * 分类选项列表
 */
const categoryOptions = computed(() => {
  const options = categories.value.map(cat => ({
    label: cat,
    value: cat
  }))
  return [
    { label: '全部', value: null },
    ...options
  ]
})

/**
 * 是否显示新增卡片
 */
const showCreateCard = computed(() => {
  return selectedToolType.value !== 'BUILTIN'
})

/**
 * 处理新增
 */
/** OpenAPI 导入弹窗可见性 */
const openApiImportVisible = ref<boolean>(false)

function handleCreate() {
  currentData.value = undefined
  formVisible.value = true
}

/**
 * 处理查看
 */
async function handleView(id: string) {
  const response = await toolApi.detail(id)
  const data = response.data.data
  const inputSchemaList = data.inputSchema || []

  AppModalApi.open({
    title: '工具详情',
    titleIcon: ToolOutlined,
    footer: null,
    content: h('div', {}, [
      h('p', {}, [h('strong', '关联智能体: '), data.used?.length ? data.used.join('、') : '无']),
      h('p', {}, [h('strong', '工具类型: '), data.toolType === 'BUILTIN' ? '内置' : '自定义']),
      h('p', {}, [h('strong', '分类: '), data.category]),
      h('p', {}, [h('strong', '名称: '), data.name]),
      h('p', {}, [h('strong', '工具编号: '), data.toolId]),
      ...(data.classPath ? [ h('p', {}, [h('strong', '类路径: '), data.classPath])] : []),
      h('p', {}, [h('strong', '描述: '), data.description]),
      h('p', {}, [h('strong', '是否需要确认: '), data.needConfirm ? '是':'否']),
      h('p', {}, [h('strong', '结果直接返回: '), data.returnDirect ? '是':'否']),
      ...(data.outputSchema ? [h('p', {}, h('strong', '输出格式:')),
        h('pre', {}, JSON.stringify(data.outputSchema, null, 2))] : []),
      ...(data.language ? [ h('p', {}, [h('strong', '语言: '), data.language])] : []),
      ...(Array.isArray(inputSchemaList) && inputSchemaList.length > 0 ? [
        h('p', {}, h('strong', '输入参数:')),
        h('div', { style: { marginTop: '8px' } }, inputSchemaList.map((param: { name: string; required: boolean; type: string; description: string; defaultValue: string }) => h('div', { style: { marginBottom: '8px', paddingLeft: '16px' } }, [
            h('div', {}, [
              h('strong', `${param.name}`),
              param.required ? h('span', { style: { color: 'red', marginLeft: '4px' } }, '*') : null,
              h('span', { style: { marginLeft: '8px', color: '#666' } }, `(${param.type})`),
              param.defaultValue ? h('span', { style: {  marginLeft: '8px', color: '#666' } }, `默认值: ${param.defaultValue}`) : null
            ]),
            // h('div', { style: { color: '#666', fontSize: '12px' } }, param.description),
          ])))
      ] : []),
      ...(data.code ? [
        h('p', {}, h('strong', '代码:')),
        h('pre', { style: { background: '#f5f5f5', padding: '12px', borderRadius: '4px' } }, data.code)
      ] : [])
    ])
  })
}

/**
 * 处理编辑
 */
async function handleEdit(id: string) {
  const response = await toolApi.detail(id)
  currentData.value = response.data.data
  formVisible.value = true
}

/**
 * 处理状态
 */
async function handleEnable(id: string) {
  const response = await toolApi.detail(id)
  const { enabled } = response.data.data

  const used = await store.checkUsedWithAgent(id)
  if (used.length > 0 && enabled) {
    Modal.confirm({
      title: '二次确认',
      content: `该工具正在被 [ ${used.join('、')} ] 智能体引用，禁用后可能会影响上述智能体的正常使用！`,
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
 * 重置列表状态并重建 InfiniteLoading 组件
 */
function resetListAndRebuild() {
  list.value = [];
  store.resetPagination();
  isFirstLoad.value = true;
  infiniteLoadingKey.value++;
}

/**
 * 切换资源可见性（仅自己可见 / 组织公开）
 */
async function handleVisibility(payload: { id: string; visibility: 'PRIVATE' | 'TENANT' }) {
  await changeResourceVisibility('TOOL', payload.id, payload.visibility)
  message.success(payload.visibility === 'PRIVATE' ? '已恢复默认访问范围' : '已扩大共享范围')
  resetListAndRebuild()
}

/**
 * 处理删除
 */
async function handleDelete(id: string) {
  const used = await store.checkUsedWithAgent(id)
  if (used.length > 0) {
    Modal.confirm({
      title: '二次确认',
      content: `该工具正在被 [ ${used.join('、')} ] 智能体引用，删除后可能会影响上述智能体的正常使用！`,
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
function handleFormSuccess() {
  resetListAndRebuild()
  store.fetchCategories()
}

/**
 * OpenAPI 导入完成：刷新分类与列表
 */
function handleOpenApiImported() {
  resetListAndRebuild()
  store.fetchCategories()
}

/**
 * 处理搜索
 */
function handleSearch() {
  store.setKeyword(keyword.value)
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
 * 监听筛选条件变化，重置状态并重建 InfiniteLoading
 */
watch([selectedToolType, selectedCategory, keyword, scope, () => spaceStore.selectedSpace], () => {
  list.value = [];
  store.resetPagination();
  isFirstLoad.value = true;
  infiniteLoadingKey.value++;
});

onMounted(() => {
  store.fetchCategories()
})
</script>

<template>
  <div class="tool-page">
    <section class="intro-section">
      <h3 class="intro-title">工具</h3>
      <p class="intro-desc text-secondary">
        工具模块是智能体能力的“执行层扩展中枢”，通过将内部系统能力、外部API服务、自定义业务逻辑封装为标准化工具，赋予智能体感知环境、执行操作、处理复杂任务的能力。精心设计的工具生态能让智能体从“对话助手”升级为“行动伙伴”。
      </p>
    </section>

    <section class="filter-section flex justify-between items-center">
      <div class="filter-left">
        <ResourceScopeTabs v-model:value="scope" />
        <ASegmented
          v-model:value="selectedToolType"
          :options="toolTypeOptions"
        />
      </div>

      <div class="filter-right flex items-center gap-md">
        <AButton
          v-permission="['tool:openapi:import']"
          :disabled="!spaceStore.canCreate"
          @click="openApiImportVisible = true"
        >
          <template #icon><ImportOutlined /></template>
          OpenAPI 导入
        </AButton>
        <ASelect
          v-model:value="selectedCategory"
          placeholder="选择分类"
          style="width: 200px;border-radius: 6px;"
        >
          <ASelectOption v-for="opt in categoryOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ASelectOption>
        </ASelect>

        <AInput
          v-model:value="keyword"
          placeholder="搜索工具名称"
          style="width: 300px;"
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
        <CreateCard v-if="showCreateCard && spaceStore.canCreate" @click="handleCreate" v-permission="['tool:config:add']" />

        <OpenApiImportModal v-model:open="openApiImportVisible" @success="handleOpenApiImported" />

        <ToolCard
          v-for="item in list"
          :key="item.id"
          :data="item"
          @view="handleView"
          @edit="handleEdit"
          @enable="handleEnable"
          @delete="handleDelete"
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

    <ToolForm
      v-model:visible="formVisible"
      :data="currentData"
      :categories="categories"
      @success="handleFormSuccess"
    />
    <ResourceOwnershipModal v-model:open="ownershipOpen" resource-type="TOOL" :resource-id="ownershipId" @changed="store.resetAndFetch()" />
    <ResourceGrantModal
      v-model:open="grantOpen"
      resource-type="TOOL"
      :resource-id="grantResourceId"
      :resource-name="grantResourceName"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/tool/index.scss' as *;
</style>
