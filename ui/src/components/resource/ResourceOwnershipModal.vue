<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { useAccountStore } from '@/stores'
import { useResourceSpaceStore } from '@/stores/modules/resourceSpace'
import * as resourceApi from '@/api/resource'
import type { ResourceVisibilityVO, ResourceGrantAccountVO } from '@/api/resource'

const props = defineProps<{ open: boolean; resourceType: string; resourceId: string | null; resourceName?: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; changed: [] }>()
const visible = computed({ get: () => props.open, set: value => emit('update:open', value) })
const accountStore = useAccountStore()
const spaceStore = useResourceSpaceStore()
const resource = ref<ResourceVisibilityVO>({})
const ownershipType = ref('PERSONAL')
const departmentId = ref<string>()
const ownerId = ref<string>()
const maintainerId = ref<string>()
const accounts = ref<ResourceGrantAccountVO[]>([])
const loading = ref(false)
const saving = ref(false)
let viewVersion = 0
let searchVersion = 0
const types = computed(() => [
  ...(accountStore.isAdmin || resource.value.ownershipType === 'PERSONAL' ? [{ value: 'PERSONAL', label: '个人资产' }] : []),
  { value: 'DEPARTMENT', label: '部门资产' },
  ...(accountStore.isAdmin ? [{ value: 'PLATFORM', label: '平台公共资产' }] : [])
])
const departments = computed(() => spaceStore.spaces.filter(item => item.departmentId != null)
  .map(item => ({ value: String(item.departmentId), label: item.name })))
const accountOptions = computed(() => {
  const options = accounts.value.map(account => ({
    value: String(account.id), label: `${account.nickname || account.username || account.id}${account.deptName ? ` · ${account.deptName}` : ''}`
  }))
  for (const id of [ownerId.value, maintainerId.value]) {
    if (id && !options.some(option => option.value === id)) options.push({ value: id, label: `账号 ${id}` })
  }
  return options
})

async function searchAccounts(keyword = '') {
  if (!props.resourceId) return
  const version = viewVersion
  const search = ++searchVersion
  try {
    const result = await resourceApi.grantAccounts(props.resourceType, props.resourceId, keyword)
    if (version === viewVersion && search === searchVersion) accounts.value = result.data.data || []
  } catch (error) {
    if (version === viewVersion && search === searchVersion) message.error('账号列表加载失败，请重新搜索')
    console.warn('加载资源移交候选账号失败', error)
  }
}

async function save() {
  if (!props.resourceId || !resource.value.canTransfer) return
  if (ownershipType.value === 'PERSONAL' && !ownerId.value) { message.warning('请选择归属账号'); return }
  if (ownershipType.value === 'DEPARTMENT' && !departmentId.value) { message.warning('请选择所属部门'); return }
  const resourceId = props.resourceId
  const resourceType = props.resourceType
  const version = viewVersion
  const data = {
    ownershipType: ownershipType.value,
    ownerId: ownershipType.value === 'PERSONAL' ? ownerId.value : undefined,
    departmentId: ownershipType.value === 'DEPARTMENT' ? departmentId.value : undefined,
    maintainerId: ownershipType.value === 'PERSONAL' ? undefined : maintainerId.value
  }
  Modal.confirm({
    title: '确认移交资源',
    content: '归属变更后将按新归属判断维护权限。既有访问授权保留，共享范围会随新归属部门重新计算。',
    async onOk() {
      saving.value = true
      try {
        await resourceApi.transferOwnership(resourceType, resourceId, data)
        if (version === viewVersion) {
          message.success('资源归属已更新')
          emit('changed')
          visible.value = false
        }
      } finally { if (version === viewVersion) saving.value = false }
    }
  })
}

watch(() => [props.open, props.resourceType, props.resourceId] as const, async ([open, type, id]) => {
  const version = ++viewVersion
  resource.value = {}
  accounts.value = []
  ownerId.value = undefined
  maintainerId.value = undefined
  departmentId.value = undefined
  if (!open || !id) return
  loading.value = true
  try {
    const [response] = await Promise.all([resourceApi.visibilityMap(type, [id]), spaceStore.load()])
    if (version !== viewVersion) return
    resource.value = response.data.data?.[id] || {}
    ownershipType.value = resource.value.ownershipType || 'PERSONAL'
    ownerId.value = resource.value.ownerId == null ? undefined : String(resource.value.ownerId)
    maintainerId.value = resource.value.maintainerId == null ? undefined : String(resource.value.maintainerId)
    departmentId.value = resource.value.owningDeptId == null ? undefined : String(resource.value.owningDeptId)
    if (resource.value.canTransfer) await searchAccounts()
  } catch (error) {
    if (version === viewVersion) message.error('资源归属加载失败，请关闭后重试')
    console.warn('加载资源归属失败', error)
  } finally { if (version === viewVersion) loading.value = false }
}, { immediate: true })
</script>

<template>
  <AModal v-model:open="visible" :title="`资源归属${resourceName ? ` · ${resourceName}` : ''}`" :confirm-loading="saving"
    :ok-button-props="{ disabled: loading || !resource.canTransfer }" @ok="save">
    <ASpin :spinning="loading">
      <AForm layout="vertical">
        <AFormItem label="资产归属">
          <ASelect v-model:value="ownershipType" :options="types" />
        </AFormItem>
        <AFormItem v-if="ownershipType === 'PERSONAL'" label="归属账号">
          <ASelect v-model:value="ownerId" show-search :filter-option="false" :options="accountOptions" placeholder="搜索并选择账号" @search="searchAccounts" />
        </AFormItem>
        <AFormItem v-if="ownershipType === 'DEPARTMENT'" label="所属部门">
          <ASelect v-model:value="departmentId" :options="departments" placeholder="选择所属部门" />
        </AFormItem>
        <AFormItem v-if="ownershipType !== 'PERSONAL'" label="指定维护人（可选）">
          <ASelect v-model:value="maintainerId" allow-clear show-search :filter-option="false" :options="accountOptions"
            placeholder="部门维护人须属于该部门或下级部门" @search="searchAccounts" />
        </AFormItem>
        <p class="ownership-hint">部门资产由所属部门及下级成员使用，负责人和指定维护人维护。平台公共资产由管理员或指定维护人维护。每项操作仍需对应资源权限。</p>
      </AForm>
    </ASpin>
  </AModal>
</template>

<style scoped>
.ownership-hint { color: var(--color-text-secondary); line-height: 1.7; font-size: 13px; }
</style>
