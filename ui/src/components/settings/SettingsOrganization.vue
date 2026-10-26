/**
 * 系统设置 - 组织与成员
 *
 * 「部门即组织」后组织树就是组织架构本身：
 * - 左侧每个组织节点直接带行内操作（新增下级 / 查看详情），树即管理入口；
 * - 点击组织节点，右侧账号列表就切换到该组织（含下级）的成员；
 * - 账号归属、角色、密码等账号级维护仍在右侧列表中完成。
 *
 */
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { PlusOutlined, ProfileOutlined } from '@ant-design/icons-vue'
import * as departmentApi from '@/api/department'
import { useAccountStore } from '@/stores'
import type { Department, DepartmentVO } from '@/types/system'
import SettingsOrganizationModal from './SettingsOrganizationModal.vue'
import SettingsTreePanel from './SettingsTreePanel.vue'
import SettingsUserList from './SettingsUserList.vue'
import type { SettingsTreeAction, SettingsTreeNode } from './treeTypes'

const accountStore = useAccountStore()

/** 左侧组织树 */
const treeData = ref<DepartmentVO[]>([])
const treeLoading = ref(false)
/** 当前选中的组织（null = 全部组织） */
const selectedDeptId = ref<string | number | null>(null)

/** 组织操作弹窗 */
const modalOpen = ref(false)
const modalMode = ref<'detail' | 'create' | 'edit'>('create')
const modalDepartment = ref<DepartmentVO | null>(null)
const modalParentId = ref<string | number | null>(null)

/** 是否拥有新增组织权限 */
const canAdd = computed(() => accountStore.hasPerm('system:dept:add'))
/** 选中的组织对象 */
const selectedDepartment = computed(() =>
  selectedDeptId.value === null ? null : findDepartment(selectedDeptId.value)
)
/** 组织总数（含全部层级） */
const departmentCount = computed(() => countDepartments(treeData.value))

const treeNodes = computed<SettingsTreeNode[]>(() => {
  const convert = (nodes: DepartmentVO[]): SettingsTreeNode[] => nodes.map((node) => ({
    id: node.id as string | number,
    name: node.name ?? '',
    badge: node.memberCount ? Number(node.memberCount) : undefined,
    children: node.children?.length ? convert(node.children) : undefined
  }))
  return convert(treeData.value)
})

/** 组织树行内操作：新增下级（需权限）+ 查看详情 */
const treeActions = computed<SettingsTreeAction[]>(() => {
  const actions: SettingsTreeAction[] = []
  if (canAdd.value) {
    actions.push({ key: 'add-child', title: '新增下级组织', icon: PlusOutlined })
  }
  actions.push({ key: 'detail', title: '查看组织详情', icon: ProfileOutlined })
  return actions
})

function findDepartment(id: string | number, nodes: DepartmentVO[] = treeData.value): DepartmentVO | null {
  for (const node of nodes) {
    if (String(node.id) === String(id)) {
      return node
    }
    const hit = node.children?.length ? findDepartment(id, node.children) : null
    if (hit) {
      return hit
    }
  }
  return null
}

function countDepartments(nodes: DepartmentVO[]): number {
  return nodes.reduce((total, node) => total + 1 + countDepartments(node.children ?? []), 0)
}

async function loadTree() {
  treeLoading.value = true
  try {
    const res = await departmentApi.tree()
    treeData.value = res.data.data ?? []
    // 选中的组织已被删除时回落到「全部组织」
    if (selectedDeptId.value !== null && !findDepartment(selectedDeptId.value)) {
      selectedDeptId.value = null
    }
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    treeLoading.value = false
  }
}

function selectDept(id: string | number | null) {
  selectedDeptId.value = id
}

function openCreate(parentId?: string | number | null) {
  modalMode.value = 'create'
  modalDepartment.value = null
  modalParentId.value = parentId ?? null
  modalOpen.value = true
}

function openDetail(id: string | number) {
  const target = findDepartment(id)
  if (!target) {
    return
  }
  modalMode.value = 'detail'
  modalDepartment.value = target
  modalParentId.value = null
  modalOpen.value = true
}

function handleTreeAction(key: string, node: SettingsTreeNode) {
  if (key === 'add-child') {
    openCreate(node.id)
    return
  }
  if (key === 'detail') {
    openDetail(node.id)
  }
}

/** 新建成功后直接选中新组织；编辑成功后保持当前选中 */
async function handleSaved(department?: Department) {
  const created = modalMode.value === 'create'
  await loadTree()
  const newId = department?.id
  if (created && newId !== undefined && newId !== null) {
    selectedDeptId.value = newId
  }
}

function handleRemoved(id: string | number) {
  if (selectedDeptId.value !== null && String(selectedDeptId.value) === String(id)) {
    selectedDeptId.value = null
  }
  modalDepartment.value = null
  void loadTree()
}

onMounted(() => void loadTree())
</script>

<template>
  <section class="settings-organization">
    <SettingsTreePanel
      title="组织架构"
      all-label="全部组织"
      :nodes="treeNodes"
      :selected="selectedDeptId"
      :loading="treeLoading"
      :actions="treeActions"
      @select="selectDept"
      @refresh="loadTree"
      @action="handleTreeAction"
    >
      <template #header-actions>
        <span class="settings-organization-count">{{ departmentCount }} 个组织</span>
        <AButton v-if="canAdd" type="text" size="small" title="新增顶级组织" @click="openCreate()">
          <template #icon><PlusOutlined /></template>
        </AButton>
      </template>
    </SettingsTreePanel>

    <div class="settings-organization-main">
      <SettingsUserList
        :department-id="selectedDeptId"
        :department-name="selectedDepartment?.name ?? ''"
        :departments="treeData"
        @changed="loadTree"
        @select-department="selectDept"
      />
    </div>

    <SettingsOrganizationModal
      v-model:open="modalOpen"
      :mode="modalMode"
      :department="modalDepartment"
      :parent-id="modalParentId"
      :departments="treeData"
      @edit="modalMode = 'edit'"
      @saved="handleSaved"
      @removed="handleRemoved"
    />
  </section>
</template>

<style scoped lang="scss">
.settings-organization {
  display: flex;
  height: 100%;
  min-height: 0;
}

// 组织树是本页的主入口，比通用树面板略宽，给行内操作按钮留出位置
.settings-organization :deep(.settings-tree-panel) {
  width: 268px;
}

.settings-organization-count {
  margin-right: var(--spacing-xs);
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.settings-organization-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  padding-left: var(--spacing-md);
}

.settings-organization-main > :deep(.settings-user) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 760px) {
  .settings-organization {
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .settings-organization :deep(.settings-tree-panel) {
    width: 100%;
    height: 200px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border-light, #eee);
  }

  .settings-organization-main {
    padding-left: 0;
  }
}
</style>
