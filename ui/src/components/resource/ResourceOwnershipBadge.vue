<script setup lang="ts">
import { computed } from 'vue'
import { GlobalOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import type { ResourceVisibilityVO } from '@/api/resource'

const props = defineProps<{ resource: ResourceVisibilityVO }>()
const label = computed(() => props.resource.spaceName || (props.resource.ownershipType === 'DEPARTMENT'
  ? '部门资产' : props.resource.ownershipType === 'PLATFORM' ? '平台公共' : '个人资产'))
const icon = computed(() => props.resource.ownershipType === 'DEPARTMENT' ? TeamOutlined
  : props.resource.ownershipType === 'PLATFORM' ? GlobalOutlined : UserOutlined)
const scopeLabel = computed(() => props.resource.visibility === 'TENANT'
  ? props.resource.ownershipType === 'PLATFORM' ? '' : '已共享'
  : props.resource.ownershipType === 'PLATFORM' ? '访问受限' : '')
const description = computed(() => {
  if (props.resource.visibility === 'TENANT') return props.resource.ownershipType === 'PLATFORM'
    ? '平台公共资源，按权限使用' : '已扩大共享范围，单独的账号和部门授权继续生效'
  return props.resource.ownershipType === 'DEPARTMENT' ? '所属部门及下级成员可使用，单独授权继续生效'
    : props.resource.ownershipType === 'PLATFORM' ? '仅管理员和指定维护人可见，单独授权继续生效'
      : '个人范围，单独的账号和部门授权继续生效'
})
</script>

<template>
  <span v-if="resource.ownershipType" class="ownership-badge" :title="`${label} · ${description}`">
    <component :is="icon" class="ownership-icon" aria-hidden="true" />
    <span class="ownership-label">{{ label }}</span>
    <span v-if="scopeLabel" class="ownership-scope">· {{ scopeLabel }}</span>
  </span>
</template>

<style scoped>
.ownership-badge { display: flex; align-items: center; gap: 5px; min-width: 0; max-width: 100%; color: var(--color-text-secondary, #8b919c); font-size: 11px; line-height: 17px; font-weight: 400; }
.ownership-icon { flex-shrink: 0; font-size: 11px; opacity: 0.85; }
.ownership-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ownership-scope { flex-shrink: 0; white-space: nowrap; color: #78889b; }
</style>
