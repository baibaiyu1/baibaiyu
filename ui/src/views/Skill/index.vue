/**
 * 技能管理主页面
 *
 */
<script setup lang="ts">
/* eslint-disable vue/multi-word-component-names */
import { onMounted, ref, computed, h, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { changeResourceVisibility } from '@/utils/resourceVisibility'
import {SearchOutlined, AppstoreOutlined, ShopOutlined} from '@ant-design/icons-vue'
import { useSkillStore } from '@/stores'
import { storeToRefs } from 'pinia'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'
import * as skillApi from '@/api/skill'
import SkillCard from '@/components/skill/SkillCard.vue'
import ResourceOwnershipModal from '@/components/resource/ResourceOwnershipModal.vue'
import ResourceGrantModal from '@/components/resource/ResourceGrantModal.vue'
import CreateCard from '@/components/skill/CreateCard.vue'
import ImportLocalForm from '@/components/skill/ImportLocalForm.vue'
import ImportGitForm from '@/components/skill/ImportGitForm.vue'
import ImportUploadForm from '@/components/skill/ImportUploadForm.vue'
import SkillWorkspaceImportModal from '@/components/skill/SkillWorkspaceImportModal.vue'
import SkillToolLinkModal from '@/components/skill/SkillToolLinkModal.vue'
import {AppModalApi} from "@/components/common/AppModalApi.ts";
import AppInfiniteLoading from '@/components/common/AppInfiniteLoading.vue'
import ResourceScopeTabs from '@/components/resource/ResourceScopeTabs.vue'

const spaceStore = useResourceSpaceStore()
const store = useSkillStore()
const router = useRouter()
const route = useRoute()
const { list, categories, selectedCategory, keyword, scope, loading, hasMore } = storeToRefs(store)
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

const importLocalVisible = ref(false)
const importGitVisible = ref(false)
const importUploadVisible = ref(false)
/** 「AI 对话创建」导入弹窗状态 */
const importWorkspaceVisible = ref(false)

/** 关联工具弹窗状态 */
const toolLinkVisible = ref(false)
const toolLinkSkillId = ref<string>('')

const infiniteLoadingKey = ref(0)

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

function handleCreate() {
  router.push({ name: 'SkillEditorNew' })
}

function handleEdit(id: string) {
  router.push({ name: 'SkillEditor', params: { id } })
}

/**
 * 处理装载本地技能包
 */
function handleImportLocal() {
  importLocalVisible.value = true
}

/**
 * 处理导入 Git 技能包
 */
function handleImportGit() {
  importGitVisible.value = true
}

/**
 * 处理导入压缩包技能
 */
function handleImportUpload() {
  importUploadVisible.value = true
}

/**
 * 处理「AI 对话创建」：从会话工作空间导入技能包
 */
function handleImportWorkspace() {
  importWorkspaceVisible.value = true
}

/**
 * 跳转到 SkillHub 技能市场
 */
function handleGoToSkillHub() {
  router.push({ name: 'SkillHub' })
}

/**
 * 处理关联工具
 */
function handleToolLink(id: string) {
  toolLinkSkillId.value = id
  toolLinkVisible.value = true
}

/**
 * 处理查看
 */
async function handleView(id: string) {
  const response = await skillApi.detail(id)
  const data = response.data.data

  AppModalApi.open({
    title: '技能包详情',
    titleIcon: AppstoreOutlined,
    footer: null,
    content: h('div', {}, [
      h('p', {}, [h('strong', '关联智能体: '), data.used?.length ? data.used.join('、') : '无']),
      h('p', {}, [h('strong', '分类: '), data.category]),
      h('p', {}, [h('strong', '名称: '), data.name]),
      h('p', {}, [h('strong', '别名: '), data.alias?.trim() || '无']),
      h('p', {}, [h('strong', '描述: '), data.description]),
      h('p', {}, [h('strong', '是否关联工具: '), data.tools?.length ? '是' : '否']),
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
 * 切换资源可见性（仅自己可见 / 组织公开）
 */
async function handleVisibility(payload: { id: string; visibility: 'PRIVATE' | 'TENANT' }) {
  await changeResourceVisibility('SKILL', payload.id, payload.visibility)
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
      content: `该技能包正在被 [ ${used.join('、')} ] 智能体引用，删除后可能会影响上述智能体的正常使用！`,
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
 * 处理设置分类（刷新分类列表和卡片数据）
 */
async function handleSetCategory() {
  await store.fetchCategories()
  await store.resetAndFetch()
  isFirstLoad.value = true
  infiniteLoadingKey.value++
}

/**
 * 处理编辑别名成功（保持当前筛选条件刷新列表）
 */
function handleSetAlias() {
  resetListAndRebuild()
}

/**
 * 处理导入成功：切换到导入分类并主动刷新列表（避免分类筛选 + InfiniteLoading 未重载导致不可见）
 */
async function handleImportSuccess(importCategory?: string) {
  await store.fetchCategories()
  keyword.value = ''
  store.setKeyword('')
  store.setCategory(importCategory ?? null)
  await store.resetAndFetch()
  isFirstLoad.value = true
  infiniteLoadingKey.value++
}

/**
 * 处理关联工具成功
 */
function handleToolLinkSuccess() {
  resetListAndRebuild()
}

/**
 * 处理搜索
 */
function handleSearch() {
  store.setKeyword(keyword.value)
}

/**
 * 处理状态
 */
async function handleEnable(id: string) {
  const response = await skillApi.detail(id)
  const { enabled } = response.data.data

  const used = await store.checkUsedWithAgent(id)
  if (used.length > 0 && enabled) {
    Modal.confirm({
      title: '二次确认',
      content: `该技能包正在被 [ ${used.join('、')} ] 智能体引用，禁用后可能会影响上述智能体的正常使用！`,
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
  // 首次加载使用 page=1，后续使用 loadMore
  if (isFirstLoad.value) {
    isFirstLoad.value = false;
    if (list.value.length > 0) {
      // 如果已有数据（如从缓存恢复），直接完成
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

  // 非首次加载，使用 loadMore 加载下一页
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
 * 是否首次加载
 */
const isFirstLoad = ref(true);

/**
 * 监听筛选条件变化，重置状态并重建 InfiniteLoading
 */
watch([selectedCategory, keyword, scope, () => spaceStore.selectedSpace], () => {
  // 重置列表和分页状态
  list.value = [];
  store.resetPagination();
  // 重置首次加载标志
  isFirstLoad.value = true;
  // 强制重建 InfiniteLoading 组件
  infiniteLoadingKey.value++;
});

onMounted(() => {
  store.fetchCategories()
  store.resetAndFetch()
})

/**
 * 监听路由 query 参数变化，从 SkillHub 返回时刷新列表
 */
watch(() => route.query.refresh, (val) => {
  if (val) {
    store.fetchCategories()
    resetListAndRebuild()
    // 清除 query 参数，避免重复刷新
    router.replace({ query: {} })
  }
})
</script>

<template>
  <div class="skill-page">
    <section class="intro-section">
      <h3 class="intro-title">技能包</h3>
      <p class="intro-desc text-secondary">
        技能是智能体的“专业能力装配中心”，通过模块化、可插拔的技能包体系，让智能体能够根据不同场景需求动态加载专业知识库与处理逻辑，实现从通用助手到领域专家的能力跃迁。
      </p>
    </section>

    <section class="filter-section flex justify-between items-center">
      <div class="filter-left flex items-center gap-sm">
        <ASegmented
          v-model:value="selectedCategory"
          :options="categoryOptions"
        />
        <ResourceScopeTabs v-model:value="scope" />
      </div>

      <div class="filter-right flex items-center gap-sm">
        <AButton type="primary" @click="handleGoToSkillHub">
          <ShopOutlined />
          技能市场
        </AButton>
        <AInput
          v-model:value="keyword"
          placeholder="搜索技能包名称"
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
        <CreateCard v-if="spaceStore.canCreate"
          @click="handleCreate"
          @importLocal="handleImportLocal"
          @importGit="handleImportGit"
          @importUpload="handleImportUpload"
          @importWorkspace="handleImportWorkspace"
          v-permission="['skill:package:add']"
        />

        <SkillCard
          v-for="item in list"
          :key="item.id"
          :data="item"
          :categories="categories"
          @view="handleView"
          @edit="handleEdit"
          @set-category="handleSetCategory"
          @set-alias="handleSetAlias"
          @enable="handleEnable"
          @delete="handleDelete"
          @visibility="handleVisibility"
          @grants="openGrants"
          @ownership="openOwnership"
          @tool-link="handleToolLink"
        />
      </div>

      <AppInfiniteLoading
        :loading-key="infiniteLoadingKey"
        @infinite="handleInfiniteLoading"
      />
    </section>

    <ImportLocalForm
      v-model:visible="importLocalVisible"
      @success="handleImportSuccess"
    />

    <ImportGitForm
      v-model:visible="importGitVisible"
      @success="handleImportSuccess"
    />

    <ImportUploadForm
      v-model:visible="importUploadVisible"
      :categories="categories"
      :default-category="selectedCategory"
      @success="handleImportSuccess"
    />

    <SkillWorkspaceImportModal
      v-model:visible="importWorkspaceVisible"
      :categories="categories"
      :default-category="selectedCategory"
      @success="handleImportSuccess"
    />
    <SkillToolLinkModal
      v-model:visible="toolLinkVisible"
      :skill-id="toolLinkSkillId"
      @success="handleToolLinkSuccess"
    />
    <ResourceOwnershipModal v-model:open="ownershipOpen" resource-type="SKILL" :resource-id="ownershipId" @changed="store.resetAndFetch()" />
    <ResourceGrantModal
      v-model:open="grantOpen"
      resource-type="SKILL"
      :resource-id="grantResourceId"
      :resource-name="grantResourceName"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/skill/index.scss' as *;
</style>
