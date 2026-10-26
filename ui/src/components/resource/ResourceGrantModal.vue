<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DeleteOutlined } from '@ant-design/icons-vue'
import * as resourceApi from '@/api/resource'
import * as departmentApi from '@/api/department'
import type { ResourceGrantAccountVO, ResourceGrantVO } from '@/api/resource'
import type { DepartmentVO } from '@/types/system'

const props = defineProps<{
  open: boolean
  resourceType: string
  resourceId: string | null
  resourceName?: string
}>()

const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const visible = computed({
  get: () => props.open,
  set: value => emit('update:open', value)
})
const loading = ref(false)
const saving = ref(false)
const grants = ref<ResourceGrantVO[]>([])
const accounts = ref<ResourceGrantAccountVO[]>([])
const accountById = ref<Record<string, ResourceGrantAccountVO>>({})
const departments = ref<DepartmentVO[]>([])
const selectedAccounts = ref<string[]>([])
const selectedDepartments = ref<string[]>([])
const allowUse = ref(true)
let viewVersion = 0
let accountSearchVersion = 0

const accountOptions = computed(() => [...new Map([
  ...selectedAccounts.value.map(id => [id, accountById.value[id]] as const),
  ...accounts.value.map(account => [String(account.id), account] as const)
].filter((entry): entry is readonly [string, ResourceGrantAccountVO] => !!entry[1])).values()].map(account => ({
  value: String(account.id),
  label: `${account.nickname || account.username || account.id} (${account.username || account.id})${account.deptName ? ` · ${account.deptName}` : ''}`
})))

const departmentOptions = computed(() => {
  const options: Array<{ value: string; label: string; disabled: boolean }> = []
  const visit = (nodes: DepartmentVO[], path: string[]) => {
    nodes.forEach(department => {
      if (department.id === undefined || department.id === null) return
      const currentPath = [...path, department.name || String(department.id)]
      options.push({
        value: String(department.id),
        label: currentPath.join(' / '),
        disabled: department.enabled === false
      })
      visit(department.children || [], currentPath)
    })
  }
  visit(departments.value, [])
  return options
})

async function loadGrants(version = viewVersion) {
  const resourceId = props.resourceId
  const resourceType = props.resourceType
  if (!resourceId) return
  loading.value = true
  try {
    const response = await resourceApi.listGrants(resourceType, resourceId)
    if (version === viewVersion) grants.value = response.data.data || []
  } catch (error) {
    console.warn('[资源授权] 查询授权失败', error)
  } finally {
    if (version === viewVersion) loading.value = false
  }
}

async function searchAccounts(keyword = '', version = viewVersion) {
  const resourceId = props.resourceId
  const resourceType = props.resourceType
  const searchVersion = ++accountSearchVersion
  if (!resourceId) return
  try {
    const response = await resourceApi.grantAccounts(resourceType, resourceId, keyword)
    if (version === viewVersion && searchVersion === accountSearchVersion) {
      accounts.value = response.data.data || []
      for (const account of accounts.value) accountById.value[String(account.id)] = account
    }
  } catch (error) {
    console.warn('[资源授权] 查询账号失败', error)
  }
}

async function loadDepartments(version = viewVersion) {
  try {
    const response = await departmentApi.tree()
    if (version === viewVersion) departments.value = response.data.data || []
  } catch (error) {
    console.warn('[资源授权] 查询部门失败', error)
  }
}

async function save() {
  const resourceId = props.resourceId
  const resourceType = props.resourceType
  const version = viewVersion
  if (!resourceId || (!selectedAccounts.value.length && !selectedDepartments.value.length)) return
  saving.value = true
  try {
    await resourceApi.saveGrants(resourceType, resourceId,
      selectedAccounts.value, selectedDepartments.value, allowUse.value)
    if (version === viewVersion) {
      message.success('授权已保存')
      selectedAccounts.value = []
      selectedDepartments.value = []
      await loadGrants(version)
    }
  } finally {
    if (version === viewVersion) saving.value = false
  }
}

async function revoke(grant: ResourceGrantVO) {
  const resourceId = props.resourceId
  const resourceType = props.resourceType
  const version = viewVersion
  if (!resourceId) return
  const target = grant.departmentId != null
    ? { departmentId: grant.departmentId } : { accountId: grant.accountId! }
  await resourceApi.removeGrant(resourceType, resourceId, target)
  if (version === viewVersion) {
    message.success('授权已撤销')
    await loadGrants(version)
  }
}

watch(() => [props.open, props.resourceType, props.resourceId] as const, ([open, , id]) => {
  const version = ++viewVersion
  accountSearchVersion++
  if (!open || !id) return
  grants.value = []
  selectedAccounts.value = []
  selectedDepartments.value = []
  accounts.value = []
  accountById.value = {}
  departments.value = []
  saving.value = false
  allowUse.value = true
  void loadGrants(version)
  void searchAccounts('', version)
  void loadDepartments(version)
}, { immediate: true })
</script>

<template>
  <AModal v-model:open="visible" :title="`授权访问 · ${resourceName || '资源'}`" :footer="null" :width="640">
    <div class="grant-editor">
      <div class="grant-form">
        <label class="grant-label" for="grant-accounts">账号</label>
        <ASelect
          id="grant-accounts"
          v-model:value="selectedAccounts"
          mode="multiple"
          :options="accountOptions"
          show-search
          :filter-option="false"
          placeholder="搜索并选择多个账号"
          class="grant-select"
          @search="searchAccounts"
        />
        <label class="grant-label" for="grant-departments">部门</label>
        <ASelect
          id="grant-departments"
          v-model:value="selectedDepartments"
          mode="multiple"
          :options="departmentOptions"
          show-search
          option-filter-prop="label"
          placeholder="选择多个部门"
          class="grant-select"
        />
        <div class="grant-hint">部门授权覆盖本部门及下级部门，成员变动后实时生效。</div>
        <div class="grant-actions">
          <ACheckbox v-model:checked="allowUse">允许使用</ACheckbox>
          <AButton type="primary" :disabled="!selectedAccounts.length && !selectedDepartments.length" :loading="saving" @click="save">授权</AButton>
        </div>
      </div>
      <ASpin :spinning="loading">
        <div v-if="!grants.length" class="grant-empty">暂无单独授权的账号或部门</div>
        <div v-for="grant in grants" :key="grant.departmentId ? `dept-${grant.departmentId}` : `account-${grant.accountId}`" class="grant-row">
          <span class="grant-name">{{ grant.departmentId ? `${grant.departmentName || grant.departmentId}（部门）` : grant.nickname || grant.username || grant.accountId }}</span>
          <span class="grant-level">{{ grant.allowUse ? '查看并使用' : '仅查看' }}</span>
          <AButton type="text" danger title="撤销授权" @click="revoke(grant)">
            <template #icon><DeleteOutlined /></template>
          </AButton>
        </div>
      </ASpin>
    </div>
  </AModal>
</template>

<style scoped>
.grant-editor { display: grid; gap: 16px; }
.grant-form { display: grid; grid-template-columns: 48px minmax(0, 1fr); align-items: center; gap: 10px; }
.grant-label { color: var(--color-text-secondary); }
.grant-select { min-width: 0; width: 100%; }
.grant-hint, .grant-actions { grid-column: 2; }
.grant-hint { color: var(--color-text-secondary); font-size: 12px; }
.grant-actions { display: flex; justify-content: space-between; align-items: center; }
.grant-empty { padding: 24px 0; color: var(--color-text-secondary); text-align: center; }
.grant-row { display: flex; align-items: center; gap: 12px; min-height: 44px; border-top: 1px solid #edf0f2; }
.grant-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.grant-level { color: var(--color-text-secondary); font-size: 12px; }
</style>
