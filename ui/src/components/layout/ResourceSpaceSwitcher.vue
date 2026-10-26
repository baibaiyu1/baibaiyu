<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AppstoreOutlined, CheckOutlined, DownOutlined, GlobalOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'

const props = defineProps<{ collapsed?: boolean }>()
const store = useResourceSpaceStore()
const open = ref(false)
const loading = ref(false)
const options = computed(() => [
  ...store.spaces.map(item => ({
    key: item.key,
    name: item.name,
    icon: item.departmentId ? TeamOutlined : item.key === 'platform' ? GlobalOutlined : UserOutlined
  })),
  { key: 'all', name: '全部可访问', icon: AppstoreOutlined }
])
const current = computed(() => options.value.find(item => item.key === store.selectedSpace)
  || { key: 'personal', name: '个人空间', icon: UserOutlined })

async function refresh() {
  loading.value = true
  try { await store.load(true) }
  catch (error) { console.warn('加载资源空间失败', error) }
  finally { loading.value = false }
}

function select(key: string) {
  store.selectedSpace = key
  open.value = false
}

watch(open, value => { if (value) void refresh() })
watch(() => props.collapsed, () => { open.value = false })
onMounted(refresh)
</script>

<template>
  <div class="sidebar-space" :class="{ 'is-collapsed': collapsed }">
    <ADropdown v-model:open="open" :trigger="['click']" placement="bottomLeft">
      <ATooltip :title="collapsed && !open ? `资源空间：${current.name}` : ''" placement="right">
        <button type="button" class="space-trigger" :class="{ 'is-open': open }"
          :aria-label="`资源空间：${current.name}`" aria-haspopup="menu" :aria-expanded="open" :title="current.name">
          <span class="space-icon"><component :is="current.icon" /></span>
          <span v-if="!collapsed" class="space-copy">
            <span class="space-caption">资源空间</span>
            <span class="space-name">{{ current.name }}</span>
          </span>
          <DownOutlined v-if="!collapsed" class="space-caret" />
        </button>
      </ATooltip>
      <template #overlay>
        <div class="space-dropdown">
          <div class="space-dropdown-heading">
            <span>切换资源空间</span><span v-if="loading" class="space-loading">加载中…</span>
          </div>
          <AMenu :selected-keys="[store.selectedSpace]" class="space-options">
            <AMenuItem v-for="option in options" :key="option.key" :class="{ 'space-option-all': option.key === 'all' }" @click="select(option.key)">
                <span class="space-option">
                  <component :is="option.icon" />
                  <span class="space-option-name" :title="option.name">{{ option.name }}</span>
                  <CheckOutlined v-if="option.key === store.selectedSpace" class="space-check" />
                </span>
            </AMenuItem>
          </AMenu>
        </div>
      </template>
    </ADropdown>
  </div>
</template>

<style scoped>
.sidebar-space { padding: 12px 16px 4px; flex-shrink: 0; }
.space-trigger { display: flex; align-items: center; gap: 10px; width: 100%; min-width: 0; padding: 10px; border: 1px solid var(--color-border-base, #e5e7eb); border-radius: 10px; background: var(--color-bg-container, #fff); color: var(--color-text-base, #333); text-align: left; cursor: pointer; transition: background-color 0.2s, border-color 0.2s; }
.space-trigger:hover, .space-trigger.is-open { background: var(--color-bg-layout, #f5f6f8); border-color: #b8c9f4; }
.space-trigger:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; }
.space-icon { display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; flex-shrink: 0; border-radius: 8px; background: #edf3ff; color: #3566cf; font-size: 17px; }
.space-copy { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.space-caption { font-size: 11px; line-height: 14px; color: var(--color-text-secondary, #888); }
.space-name { font-size: 13px; line-height: 19px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.space-caret { flex-shrink: 0; font-size: 10px; color: var(--color-text-secondary, #888); }
.is-collapsed { padding: 12px 16px 4px; }
.is-collapsed .space-trigger { justify-content: center; width: 40px; height: 40px; padding: 3px; }
.space-dropdown { min-width: 248px; max-width: min(360px, calc(100vw - 32px)); padding: 6px; border: 1px solid var(--color-border-base, #e5e7eb); border-radius: 10px; background: var(--color-bg-container, #fff); box-shadow: 0 6px 24px #00000014; }
.space-dropdown-heading { display: flex; justify-content: space-between; gap: 16px; padding: 8px 10px; font-size: 12px; color: var(--color-text-secondary, #888); }
.space-loading { font-size: 11px; }
.space-options { max-height: min(420px, 60vh); overflow-y: auto; border: 0; box-shadow: none; }
.space-options :deep(.space-option-all) { margin-top: 8px; }
.space-option { display: flex; align-items: center; gap: 10px; min-width: 0; }
.space-option-name { flex: 1; white-space: normal; overflow-wrap: anywhere; }
.space-check { flex-shrink: 0; font-size: 12px; color: #1677ff; }
</style>
