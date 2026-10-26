/**
 * 系统设置 - 角色配置
 *
 * 角色列表（序号/角色名称/权限字符/归属组织/显示顺序/数据范围/状态/创建时间/操作），
 * 支持新增、编辑（含菜单权限树）、分配数据权限、用户分配、删除。
 *
 * 「部门即组织」后角色分两档：归属组织为空 = 平台级角色（全平台可用）；
 * 非空 = 该组织（部门）的专属角色（如「XX部主管」），只能分配给该组织内的账号。
 *
 */
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ApartmentOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  TeamOutlined,
  UsergroupAddOutlined
} from '@ant-design/icons-vue'
import * as departmentApi from '@/api/department'
import * as roleApi from '@/api/role'
import * as menuApi from '@/api/menu'
import * as systemUserApi from '@/api/systemUser'
import type { DataScopeOption, DepartmentVO, SysMenuVO, SysRoleVO, SystemUserVO } from '@/types/system'

/** 角色列表 */
const roleList = ref<SysRoleVO[]>([])
const loading = ref(false)
const keyword = ref('')
/** 归属组织过滤：'' = 全部 */
const deptFilter = ref<string | number | ''>('')

/** 菜单权限树（角色编辑用） */
const menuTree = ref<SysMenuVO[]>([])
/** 数据权限范围选项 */
const dataScopeOptions = ref<DataScopeOption[]>([])
/** 组织（部门）树与平铺选项 */
const deptTree = ref<DepartmentVO[]>([])
/** 可分配用户列表（平台级角色=全平台账号；组织级角色=该组织账号） */
const allUsers = ref<SystemUserVO[]>([])
const usersLoading = ref(false)

const columns = [
  { title: '序号', key: 'index', width: 70 },
  { title: '角色名称', dataIndex: 'name', key: 'name', width: 170 },
  { title: '角色权限字符', dataIndex: 'code', key: 'code', width: 170 },
  { title: '归属组织', dataIndex: 'deptName', key: 'deptName', width: 150 },
  { title: '显示顺序', dataIndex: 'sortOrder', key: 'sortOrder', width: 90 },
  { title: '数据范围', dataIndex: 'dataScopeLabel', key: 'dataScope', width: 170 },
  { title: '角色状态', dataIndex: 'enabled', key: 'enabled', width: 90 },
  { title: '用户数', dataIndex: 'userCount', key: 'userCount', width: 80 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
  { title: '操作', key: 'action', width: 280, fixed: 'right' as const }
]

const filteredRoles = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  let list = roleList.value
  if (deptFilter.value === '__platform__') {
    list = list.filter((item) => !item.deptId)
  } else if (deptFilter.value !== '') {
    list = list.filter((item) => String(item.deptId) === String(deptFilter.value))
  }
  if (!query) {
    return list
  }
  return list.filter(
    (item) =>
      (item.name ?? '').toLowerCase().includes(query) ||
      (item.code ?? '').toLowerCase().includes(query)
  )
})

/** 组织（部门）平铺选项，用于角色归属选择 */
const deptOptions = computed(() => {
  const options: Array<{ value: string | number; label: string }> = []
  const walk = (nodes: DepartmentVO[], depth: number) => {
    for (const node of nodes) {
      options.push({
        value: node.id as string | number,
        label: `${'　'.repeat(depth)}${node.name ?? ''}`
      })
      if (node.children?.length) {
        walk(node.children, depth + 1)
      }
    }
  }
  walk(deptTree.value, 0)
  return options
})

/** 组织过滤选项（含「仅平台级」） */
const deptFilterOptions = computed(() => [
  { value: '', label: '全部角色' },
  { value: '__platform__', label: '仅平台级角色' },
  ...deptOptions.value
])

async function loadRoles() {
  loading.value = true
  try {
    const res = await roleApi.list()
    roleList.value = res.data.data ?? []
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    loading.value = false
  }
}

async function loadMeta() {
  try {
    const [menus, scopes, depts] = await Promise.all([
      menuApi.tree(),
      roleApi.dataScopes(),
      departmentApi.tree()
    ])
    menuTree.value = menus.data.data ?? []
    dataScopeOptions.value = scopes.data.data ?? []
    deptTree.value = depts.data.data ?? []
  } catch {
    // 统一由 request.ts 响应拦截器提示
  }
}

/**
 * 加载可分配用户
 *
 * 平台级角色（无归属组织）候选为全平台可见账号；
 * 组织级角色只允许该组织（含下级部门）内的账号。
 */
async function loadUsers(deptId?: string | number | null) {
  usersLoading.value = true
  try {
    const res = await systemUserApi.list(deptId ? { deptId } : undefined)
    allUsers.value = res.data.data ?? []
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    usersLoading.value = false
  }
}

/** ============ 编辑弹窗 ============ */
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = ref<{
  id?: string | number
  name: string
  code: string
  deptId?: string | number
  sortOrder: number
  enabled: boolean
  remark: string
  dataScope: string
  builtin?: boolean
}>({ name: '', code: '', deptId: undefined, sortOrder: 1, enabled: true, remark: '', dataScope: 'DEPT_AND_CHILD' })

/** 已勾选的菜单权限ID（含目录/菜单/按钮） */
const checkedMenuIds = ref<Array<string | number>>([])

/** 菜单权限树展开的节点 */
const expandedMenuKeys = ref<Array<string | number>>([])

const menuTreeData = computed(() => convertMenuTree(menuTree.value))

function convertMenuTree(nodes: SysMenuVO[]): Array<Record<string, unknown>> {
  return nodes.map((node) => ({
    key: node.id as string | number,
    title: node.name,
    isLeaf: !node.children?.length,
    children: node.children?.length ? convertMenuTree(node.children) : undefined
  }))
}

function collectAllMenuKeys(nodes: SysMenuVO[]): Array<string | number> {
  const keys: Array<string | number> = []
  const walk = (list: SysMenuVO[]) => {
    for (const node of list) {
      keys.push(node.id as string | number)
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return keys
}

function openCreate() {
  editForm.value = {
    name: '',
    code: '',
    deptId: undefined,
    sortOrder: roleList.value.length + 1,
    enabled: true,
    remark: '',
    dataScope: 'DEPT_AND_CHILD'
  }
  checkedMenuIds.value = []
  expandedMenuKeys.value = collectAllMenuKeys(menuTree.value)
  editVisible.value = true
}

function openEdit(row: SysRoleVO) {
  editForm.value = {
    id: row.id,
    name: row.name ?? '',
    code: row.code ?? '',
    deptId: row.deptId ?? undefined,
    sortOrder: row.sortOrder ?? 1,
    enabled: row.enabled !== false,
    remark: row.remark ?? '',
    dataScope: row.dataScope ?? 'DEPT_AND_CHILD',
    builtin: row.builtin
  }
  checkedMenuIds.value = [...(row.menuIds ?? [])]
  expandedMenuKeys.value = collectAllMenuKeys(menuTree.value)
  editVisible.value = true
}

async function submitEdit() {
  if (!editForm.value.name.trim()) {
    message.warning('请输入角色名称')
    return
  }
  if (!editForm.value.code.trim()) {
    message.warning('请输入角色权限字符')
    return
  }
  editSaving.value = true
  try {
    const payload = {
      name: editForm.value.name.trim(),
      code: editForm.value.code.trim(),
      deptId: editForm.value.deptId ?? null,
      sortOrder: editForm.value.sortOrder,
      enabled: editForm.value.enabled,
      remark: editForm.value.remark,
      dataScope: editForm.value.dataScope,
      menuIds: checkedMenuIds.value
    }
    const editingId = editForm.value.id
    if (editingId !== undefined && editingId !== null) {
      await roleApi.update(editingId, payload)
      message.success('角色已更新')
    } else {
      await roleApi.save(payload)
      message.success('角色创建成功')
    }
    editVisible.value = false
    await loadRoles()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    editSaving.value = false
  }
}

/** ============ 数据权限弹窗 ============ */
const scopeVisible = ref(false)
const scopeSaving = ref(false)
const scopeTarget = ref<SysRoleVO | null>(null)
const scopeDraft = ref('ALL')

function openScopeDialog(row: SysRoleVO) {
  scopeTarget.value = row
  scopeDraft.value = row.dataScope ?? 'ALL'
  scopeVisible.value = true
}

async function submitScope() {
  const target = scopeTarget.value
  if (!target) {
    return
  }
  scopeSaving.value = true
  try {
    await roleApi.updateDataScope(target.id as string | number, scopeDraft.value)
    message.success('数据权限范围已更新')
    scopeVisible.value = false
    await loadRoles()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    scopeSaving.value = false
  }
}

/** ============ 用户分配弹窗 ============ */
const assignVisible = ref(false)
const assignSaving = ref(false)
const assignTarget = ref<SysRoleVO | null>(null)
const assignDraft = ref<Array<string | number>>([])
const assignKeyword = ref('')

const filteredAssignUsers = computed(() => {
  const query = assignKeyword.value.trim().toLowerCase()
  if (!query) {
    return allUsers.value
  }
  return allUsers.value.filter(
    (item) =>
      (item.username ?? '').toLowerCase().includes(query) ||
      (item.nickname ?? '').toLowerCase().includes(query) ||
      (item.deptName ?? '').toLowerCase().includes(query)
  )
})

async function openAssignDialog(row: SysRoleVO) {
  assignTarget.value = row
  assignKeyword.value = ''
  assignVisible.value = true
  assignDraft.value = []
  // 组织级角色的候选账号限定在该组织内，避免跨组织分配被后端拒绝
  await loadUsers(row.deptId ?? null)
  try {
    const res = await roleApi.listAssignedUsers(row.id as string | number)
    assignDraft.value = res.data.data ?? []
  } catch {
    // 统一由 request.ts 响应拦截器提示
  }
}

async function submitAssign() {
  const target = assignTarget.value
  if (!target) {
    return
  }
  assignSaving.value = true
  try {
    await roleApi.assignUsers(target.id as string | number, assignDraft.value)
    message.success(`已为「${target.name}」分配 ${assignDraft.value.length} 名用户`)
    assignVisible.value = false
    await loadRoles()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    assignSaving.value = false
  }
}

function handleRemove(row: SysRoleVO) {
  Modal.confirm({
    title: '确认删除',
    icon: null,
    content: `确定要删除角色「${row.name}」吗？内置角色或已分配用户的角色无法删除。`,
    onOk: async () => {
      try {
        await roleApi.remove(row.id as string | number)
        message.success('角色已删除')
        await loadRoles()
      } catch {
        // 统一由 request.ts 响应拦截器提示
      }
    }
  })
}

onMounted(() => {
  void loadRoles()
  void loadMeta()
})
</script>

<template>
  <div class="settings-role">
    <header class="settings-role-toolbar">
      <div class="settings-role-filters">
        <AInput
          v-model:value="keyword"
          placeholder="角色名称 / 权限字符"
          allow-clear
          style="width: 240px"
        >
          <template #prefix><SearchOutlined /></template>
        </AInput>
        <ASelect
          v-model:value="deptFilter"
          :options="deptFilterOptions"
          style="width: 200px"
          show-search
          option-filter-prop="label"
        />
      </div>
      <div class="settings-role-tools">
        <span class="settings-role-total">共 {{ filteredRoles.length }} 个角色</span>
        <AButton type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新增
        </AButton>
        <AButton @click="loadRoles">
          <template #icon><ReloadOutlined /></template>
          刷新
        </AButton>
      </div>
    </header>

    <ATable
      :columns="columns"
      :data-source="filteredRoles"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1570, y: 'calc(100vh - 300px)' }"
      row-key="id"
      size="middle"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'index'">
          {{ index + 1 }}
        </template>
        <template v-else-if="column.key === 'name'">
          <span class="settings-role-name">
            <SafetyCertificateOutlined class="settings-role-icon" />
            {{ record.name }}
            <ATag
              v-if="record.builtin"
              :bordered="false"
              color="gold"
              class="settings-role-builtin"
            >
              内置
            </ATag>
          </span>
        </template>
        <template v-else-if="column.key === 'code'">
          <code class="settings-role-code">{{ record.code }}</code>
        </template>
        <template v-else-if="column.key === 'deptName'">
          <ATag v-if="record.deptName" :bordered="false" color="blue">
            <ApartmentOutlined />
            {{ record.deptName }}
          </ATag>
          <ATag v-else :bordered="false">平台级</ATag>
        </template>
        <template v-else-if="column.key === 'dataScope'">
          <ATag :bordered="false" color="blue">{{ record.dataScopeLabel || '-' }}</ATag>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <ATag :color="record.enabled === false ? 'default' : 'success'" :bordered="false">
            {{ record.enabled === false ? '停用' : '正常' }}
          </ATag>
        </template>
        <template v-else-if="column.key === 'userCount'">
          <span class="settings-role-count">
            <TeamOutlined />
            {{ record.userCount ?? 0 }}
          </span>
        </template>
        <template v-else-if="column.key === 'createdAt'">
          {{ record.createdAt || '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="settings-row-actions">
            <AButton type="link" size="small" @click="openEdit(record)">
              <template #icon><EditOutlined /></template>
              编辑
            </AButton>
            <AButton type="link" size="small" @click="openScopeDialog(record)">
              <template #icon><SafetyCertificateOutlined /></template>
              数据权限
            </AButton>
            <AButton type="link" size="small" @click="openAssignDialog(record)">
              <template #icon><UsergroupAddOutlined /></template>
              用户分配
            </AButton>
            <AButton
              type="link"
              size="small"
              danger
              :disabled="record.builtin === true"
              @click="handleRemove(record)"
            >
              <template #icon><DeleteOutlined /></template>
              删除
            </AButton>
          </div>
        </template>
      </template>
    </ATable>

    <!-- 编辑 / 新增角色 -->
    <AModal
      v-model:open="editVisible"
      :title="editForm.id ? '编辑角色' : '新增角色'"
      :confirm-loading="editSaving"
      :width="620"
      @ok="submitEdit"
    >
      <AForm layout="vertical" :model="editForm">
        <ARow :gutter="16">
          <ACol :span="12">
            <AFormItem label="角色名称" required>
              <AInput v-model:value="editForm.name" placeholder="请输入角色名称" :maxlength="100" />
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="角色权限字符" required>
              <AInput
                v-model:value="editForm.code"
                placeholder="例如：custom_role"
                :maxlength="100"
              />
            </AFormItem>
          </ACol>
        </ARow>
        <ARow :gutter="16">
          <ACol :span="12">
            <AFormItem label="显示顺序">
              <AInputNumber v-model:value="editForm.sortOrder" :min="0" style="width: 100%" />
            </AFormItem>
          </ACol>
          <ACol :span="12">
            <AFormItem label="角色状态">
              <ARadioGroup v-model:value="editForm.enabled">
                <ARadio :value="true">正常</ARadio>
                <ARadio :value="false">停用</ARadio>
              </ARadioGroup>
            </AFormItem>
          </ACol>
        </ARow>
        <AFormItem label="归属组织 / 部门">
          <ASelect
            v-model:value="editForm.deptId"
            :options="deptOptions"
            :disabled="editForm.builtin === true"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="留空 = 平台级角色（全平台可用）"
          />
          <p class="settings-dialog-hint">
            选组织即为该组织（部门）的专属角色（如「XX部主管」），只能分配给该组织内的账号；
            留空则为平台级角色。内置角色固定为平台级。
          </p>
        </AFormItem>
        <AFormItem label="数据权限范围">
          <ASelect
            v-model:value="editForm.dataScope"
            :options="dataScopeOptions"
            placeholder="请选择数据权限范围"
          />
          <p class="settings-dialog-hint">
            数据范围决定该角色能看到哪些组织的数据；「本部门及以下」为组织隔离的推荐取值。
          </p>
        </AFormItem>

        <AFormItem label="菜单权限">
          <div class="settings-role-perm-box">
            <div class="settings-role-perm-actions">
              <AButton
                type="link"
                size="small"
                @click="checkedMenuIds = collectAllMenuKeys(menuTree)"
              >
                全选
              </AButton>
              <AButton type="link" size="small" @click="checkedMenuIds = []">清空</AButton>
              <span class="settings-role-perm-count">已选 {{ checkedMenuIds.length }} 项</span>
            </div>
            <ATree
              v-model:checked-keys="checkedMenuIds"
              v-model:expanded-keys="expandedMenuKeys"
              :tree-data="menuTreeData"
              checkable
              :selectable="false"
              :height="240"
            />
          </div>
        </AFormItem>

        <AFormItem label="备注">
          <ATextarea
            v-model:value="editForm.remark"
            :rows="3"
            placeholder="请输入备注"
            :maxlength="500"
          />
        </AFormItem>
      </AForm>
    </AModal>

    <!-- 分配数据权限 -->
    <AModal
      v-model:open="scopeVisible"
      title="分配数据权限"
      :confirm-loading="scopeSaving"
      :width="480"
      @ok="submitScope"
    >
      <AForm layout="vertical">
        <AFormItem label="角色名称">
          <AInput :value="scopeTarget?.name" disabled />
        </AFormItem>
        <AFormItem label="权限字符">
          <AInput :value="scopeTarget?.code" disabled />
        </AFormItem>
        <AFormItem label="权限范围">
          <ASelect v-model:value="scopeDraft" :options="dataScopeOptions" />
        </AFormItem>
      </AForm>
      <p class="settings-dialog-hint">
        保存后，该角色在用户列表及业务数据中的可见范围会同步更新。
      </p>
    </AModal>

    <!-- 分配用户 -->
    <AModal
      v-model:open="assignVisible"
      title="分配用户权限"
      :confirm-loading="assignSaving"
      :width="720"
      @ok="submitAssign"
    >
      <div class="settings-assign-header">
        <span>
          角色：<strong>{{ assignTarget?.name }}</strong>
          ，已选择 <strong>{{ assignDraft.length }}</strong> 名用户
        </span>
        <div class="settings-assign-tools">
          <AInput
            v-model:value="assignKeyword"
            placeholder="用户名称 / 账号 / 部门"
            allow-clear
            size="small"
            style="width: 200px"
          >
            <template #prefix><SearchOutlined /></template>
          </AInput>
          <AButton
            type="link"
            size="small"
            @click="assignDraft = filteredAssignUsers.map((item) => item.id)"
          >
            全选当前
          </AButton>
          <AButton type="link" size="small" @click="assignDraft = []">清空</AButton>
        </div>
      </div>

      <div class="settings-assign-body">
        <AppSpin :spinning="usersLoading">
          <ACheckboxGroup v-model:value="assignDraft" class="settings-assign-list">
            <ACheckbox
              v-for="user in filteredAssignUsers"
              :key="user.id"
              :value="user.id"
              class="settings-assign-item"
            >
              <span class="settings-assign-name">{{ user.nickname || user.username }}</span>
              <span class="settings-assign-meta">
                @{{ user.username }}
                <template v-if="user.deptName"> · {{ user.deptName }}</template>
              </span>
            </ACheckbox>
          </ACheckboxGroup>
          <AEmpty v-if="filteredAssignUsers.length === 0" description="暂无匹配用户" />
        </AppSpin>
      </div>
    </AModal>
  </div>
</template>

<style scoped lang="scss">
.settings-role {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.settings-role-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  flex-wrap: wrap;
}

.settings-role-filters,
.settings-role-tools {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.settings-role-total {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.settings-role-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: var(--color-text-primary);
}

.settings-role-icon {
  color: var(--color-primary);
}

.settings-role-builtin {
  margin-left: 2px;
}

.settings-role-code {
  padding: 1px 6px;
  border-radius: 4px;
  background-color: #f2f4f7;
  color: var(--color-text-regular);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
}

.settings-role-count {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);
}

.settings-row-actions {
  display: flex;
  align-items: center;
  gap: 0;
  margin-left: -7px;
}

.settings-role-perm-box {
  border: 1px solid var(--color-border-light, #eee);
  border-radius: var(--border-radius-md);
  overflow: hidden;
}

.settings-role-perm-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 4px 10px;
  border-bottom: 1px solid var(--color-border-lighter, #f2f6fc);
  background-color: #fafbfc;
}

.settings-role-perm-count {
  margin-left: auto;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.settings-dialog-hint {
  margin: var(--spacing-sm) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.settings-assign-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-sm);
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);
  flex-wrap: wrap;
}

.settings-assign-tools {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.settings-assign-body {
  max-height: 380px;
  overflow-y: auto;
  border: 1px solid var(--color-border-light, #eee);
  border-radius: var(--border-radius-md);
}

.settings-assign-list {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.settings-assign-item {
  display: flex;
  align-items: center;
  width: 100%;
  margin: 0;
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border-extra-light, #f2f6fc);

  &:last-child {
    border-bottom: none;
  }
}

.settings-assign-name {
  color: var(--color-text-primary);
  font-weight: 500;
}

.settings-assign-meta {
  margin-left: 6px;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}
</style>
