/**
 * 系统设置 - 组织成员（账号列表）
 *
 * 账号的唯一管理入口（原「全部账户」页已合并到这里）。
 * 左侧组织树选中哪个组织，这里就展示哪个组织（含全部下级部门）的成员；
 * 选中「全部组织」时展示可见范围内的全部账号，并额外提示未归属组织的账号。
 *
 * 每行都会标出账号所属部门（展示组织全路径，便于区分同名子部门）。
 *
 * 「部门即组织」：账号的 `deptId` 决定它属于哪个组织；若为「未归属」则该账号不属于任何组织，
 * 数据范围退化为「仅本人」，需要管理员重新分配。可见范围由后端按调用者的数据权限范围收敛。
 *
 * 系统已关闭自助注册：账号只能由拥有 `system:user:add` / `system:user:remove` 的管理员在此新增或删除；
 * 新增/重置得到的明文密码只在响应中出现一次。
 *
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  CrownOutlined,
  KeyOutlined,
  LogoutOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
  TeamOutlined
} from '@ant-design/icons-vue'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'
import * as accountApi from '@/api/account'
import * as roleApi from '@/api/role'
import * as systemUserApi from '@/api/systemUser'
import { md5 } from 'js-md5'
import { useAccountStore } from '@/stores'
import type { AccountCredentialVO } from '@/types'
import type { DepartmentVO, SysRoleVO, SystemUserVO } from '@/types/system'
import UserCredentialModal from './UserCredentialModal.vue'

const props = withDefaults(defineProps<{
  /** 当前选中的组织（null = 全部组织） */
  departmentId?: string | number | null
  /** 当前选中的组织名称（用于范围提示） */
  departmentName?: string
  /** 组织树，用于展示部门全路径与「所属组织」下拉 */
  departments?: DepartmentVO[]
}>(), {
  departmentId: null,
  departmentName: '',
  departments: () => []
})

const emit = defineEmits<{
  /** 成员归属发生变化，父级需要刷新组织树 */
  (e: 'changed'): void
  /** 请求切换左侧组织选中项 */
  (e: 'select-department', id: string | number | null): void
}>()

const accountStore = useAccountStore()

/** 用户列表 */
const userList = ref<SystemUserVO[]>([])
const loading = ref(false)

/** 筛选条件 */
const keyword = ref('')
const selectedRowKeys = ref<Array<string | number>>([])

/** 是否拥有新增用户权限 */
const canAdd = computed(() => accountStore.hasPerm('system:user:add'))
/** 是否拥有删除用户权限 */
const canRemove = computed(() => accountStore.hasPerm('system:user:remove'))
/** 是否拥有重置密码权限 */
const canResetPwd = computed(() => accountStore.hasPerm('system:user:resetPwd'))
/** 是否拥有修改用户（分配组织/角色）权限 */
const canEdit = computed(() => accountStore.hasPerm('system:user:edit'))

/** 未归属组织的账号数（提示管理员尽快分配） */
const orphanCount = computed(() => userList.value.filter((user) => !user.deptId).length)

/** 组织全路径：deptId -> 「顶火集团 / 研发中心 / 平台组」 */
const deptPathMap = computed(() => {
  const map = new Map<string, string>()
  const walk = (nodes: DepartmentVO[], parents: string[]) => {
    for (const node of nodes) {
      const path = [...parents, node.name ?? ''].filter(Boolean)
      map.set(String(node.id), path.join(' / '))
      if (node.children?.length) {
        walk(node.children, path)
      }
    }
  }
  walk(props.departments, [])
  return map
})

/**
 * 表格列顺序与宽度
 * <p>
 * 操作为图标按钮并固定在右侧，正文可视宽度约 680px：
 * 前四列（用户 / 部门 / 角色 / 状态）合计 670px，保证不横向滚动时也是完整列，
 * 「邮箱 / 创建时间」作为次要信息排在后面，需要时横向滚动查看。
 */
const columns = [
  { title: '用户名称', dataIndex: 'username', key: 'user', width: 180 },
  { title: '所属部门 / 组织', dataIndex: 'deptName', key: 'dept', width: 230 },
  { title: '角色', dataIndex: 'roleNames', key: 'roles', width: 130 },
  { title: '状态', dataIndex: 'enabled', key: 'enabled', width: 130 },
  { title: '邮箱', dataIndex: 'email', key: 'email', width: 180 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160 },
  // 图标按钮固定右侧，横向滚动时操作入口常显
  { title: '操作', key: 'action', width: 210, fixed: 'right' as const }
]

/** 表格横向滚动基准宽度：列宽合计 */
const tableScrollX = columns.reduce((total, column) => total + (column.width ?? 0), 0)

/** 无数据文案：区分「本组织暂无成员」与「全部组织暂无账号」 */
const emptyText = computed(() => {
  if (props.departmentId === null) {
    return '暂无账号'
  }
  return `「${props.departmentName || '该组织'}」及下级部门暂无成员，可用「添加成员」把已有账号加入本组织`
})

async function loadUsers() {
  loading.value = true
  try {
    const res = await systemUserApi.list({
      keyword: keyword.value.trim() || undefined,
      deptId: props.departmentId ?? undefined
    })
    userList.value = res.data.data ?? []
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    loading.value = false
  }
}

/** 重新拉取列表，并让父级刷新组织树上的成员数徽标 */
async function refreshAll() {
  await loadUsers()
  emit('changed')
}

watch(
  () => props.departmentId,
  () => void loadUsers(),
  { immediate: true }
)

function search() {
  void loadUsers()
}

/** 重置只清空搜索关键字，组织范围由左侧组织树控制 */
function resetSearch() {
  keyword.value = ''
  void loadUsers()
}

/** 回到「全部组织」 */
function showAll() {
  emit('select-department', null)
}

function getAvatarText(row: SystemUserVO) {
  const source = row.nickname || row.username || '?'
  return source.charAt(0).toUpperCase()
}

/** 账号所属部门全路径（组织树里查不到时退回后端给的部门名） */
function deptPath(row: SystemUserVO) {
  if (!row.deptId) {
    return ''
  }
  return deptPathMap.value.get(String(row.deptId)) ?? row.deptName ?? ''
}

/** 组织（部门）平铺选项，用于分配组织 */
const deptFlatOptions = computed(() => {
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
  walk(props.departments, 0)
  return options
})

/** 设置所属组织弹窗 */
const deptDialogVisible = ref(false)
const deptSaving = ref(false)
const deptTarget = ref<SystemUserVO | null>(null)
const deptDraft = ref<string | number | undefined>(undefined)

function openDeptDialog(row: SystemUserVO) {
  deptTarget.value = row
  deptDraft.value = row.deptId ?? undefined
  deptDialogVisible.value = true
}

async function submitDept() {
  const target = deptTarget.value
  if (!target) {
    return
  }
  deptSaving.value = true
  try {
    await systemUserApi.updateDepartment(target.id, deptDraft.value ?? null)
    message.success(deptDraft.value ? '所属组织已更新' : '已移出组织')
    deptDialogVisible.value = false
    await refreshAll()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    deptSaving.value = false
  }
}

/** 把用户移出组织（保留账号） */
function handleRemoveFromDept(row: SystemUserVO) {
  Modal.confirm({
    title: '移出组织',
    icon: null,
    content: `确定把「${row.nickname || row.username}」移出所属组织吗？账号会保留，但将不再属于任何组织。`,
    okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await systemUserApi.removeFromDepartment(row.id)
        message.success('已移出组织')
        await refreshAll()
      } catch {
        // 统一由 request.ts 响应拦截器提示
      }
    }
  })
}

/** 添加成员：把平台上已有账号拉进当前组织 */
const memberVisible = ref(false)
const memberSaving = ref(false)
const memberDraft = ref<string | number | undefined>(undefined)
const allUsers = ref<SystemUserVO[]>([])

const memberOptions = computed(() =>
  allUsers.value
    .filter((user) => !user.deptId || String(user.deptId) !== String(props.departmentId))
    .map((user) => ({
      value: user.id,
      label: `${user.nickname || user.username}（@${user.username}）${user.deptName ? ' - 现属 ' + user.deptName : ' - 未归属组织'}`
    }))
)

async function openMemberDialog() {
  if (props.departmentId === null) {
    message.warning('请先在左侧选择要管理的组织')
    return
  }
  try {
    const res = await systemUserApi.list()
    allUsers.value = res.data.data ?? []
  } catch {
    allUsers.value = []
  }
  memberDraft.value = undefined
  memberVisible.value = true
}

async function submitMember() {
  if (props.departmentId === null || memberDraft.value === undefined) {
    message.warning('请选择要加入本组织的账号')
    return
  }
  memberSaving.value = true
  try {
    await systemUserApi.updateDepartment(memberDraft.value, props.departmentId)
    message.success('成员已加入本组织')
    memberVisible.value = false
    await refreshAll()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    memberSaving.value = false
  }
}

/** 分配角色弹窗 */
const roleDialogVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref<SystemUserVO | null>(null)
const roleDraft = ref<Array<string | number>>([])
const roleOptions = ref<SysRoleVO[]>([])

async function openRoleDialog(row: SystemUserVO) {
  roleTarget.value = row
  roleDraft.value = [...(row.roleIds ?? [])]
  roleDialogVisible.value = true
  try {
    // 只列出「平台级角色 + 该账号所属组织的角色」，部门级角色不能跨组织分配
    const res = await roleApi.list(row.deptId ? { deptId: row.deptId } : undefined)
    roleOptions.value = res.data.data ?? []
  } catch {
    roleOptions.value = []
  }
}

async function submitRoles() {
  const target = roleTarget.value
  if (!target) {
    return
  }
  roleSaving.value = true
  try {
    await systemUserApi.assignRoles(target.id, roleDraft.value)
    message.success('角色分配已保存')
    roleDialogVisible.value = false
    await loadUsers()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    roleSaving.value = false
  }
}

function toggleEnabled(row: SystemUserVO) {
  const action = row.enabled ? '禁用' : '激活'
  Modal.confirm({
    title: '确认操作',
    icon: null,
    content: `确定要${action}用户「${row.nickname || row.username}」吗？`,
    onOk: async () => {
      try {
        await accountApi.toggleEnabled(row.id as string, !row.enabled)
        message.success(`${action}成功`)
        await loadUsers()
      } catch {
        // 统一由 request.ts 响应拦截器提示
      }
    }
  })
}

/** 重置密码弹窗 */
const pwdDialogVisible = ref(false)
const pwdSaving = ref(false)
const pwdTarget = ref<SystemUserVO | null>(null)
const pwdDraft = ref('')
/** 重置时是否由管理员指定密码（否则服务端随机生成） */
const pwdCustom = ref(false)

function openPwdDialog(row: SystemUserVO) {
  pwdTarget.value = row
  pwdDraft.value = ''
  pwdCustom.value = false
  pwdDialogVisible.value = true
}

async function submitPwd() {
  const target = pwdTarget.value
  if (!target) {
    return
  }
  if (pwdCustom.value && pwdDraft.value.length < 6) {
    message.warning('密码长度至少 6 位')
    return
  }
  pwdSaving.value = true
  try {
    const res = await accountApi.resetPassword(
      target.id as string,
      pwdCustom.value ? md5(pwdDraft.value) : undefined
    )
    pwdDialogVisible.value = false
    credential.value = res.data.data
    credentialVisible.value = true
    await loadUsers()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    pwdSaving.value = false
  }
}

/** 新增用户弹窗 */
const createDialogVisible = ref(false)
const createSaving = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = ref<{
  nickname: string
  username: string
  email: string
  deptId?: string | number
  password: string
  customPassword: boolean
}>({
  nickname: '',
  username: '',
  email: '',
  deptId: undefined,
  password: '',
  customPassword: false
})

/**
 * 新增用户表单规则
 * <p>
 * username 与登录页（`pages/Login.vue`）保持完全一致：登录表单同样限制 4-20 个字符，
 * 这里放宽或收紧都会造出「建得出来、却输不进登录框」的账号。
 * nickname 受限于 `gmd_faq_account.nickname varchar(10)`，超过会直接写库报错。
 */
const createRules: Record<string, Rule[]> = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 10, message: '昵称长度为2-10个字符', trigger: 'blur' },
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '用户名长度为4-20个字符', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }],
}

function openCreateDialog() {
  createForm.value = {
    nickname: '',
    username: '',
    email: '',
    // 默认归属到左侧选中的组织，减少一次选择
    deptId: props.departmentId ?? undefined,
    password: '',
    customPassword: false
  }
  createDialogVisible.value = true
}

async function submitCreate() {
  try {
    await createFormRef.value?.validate()
  } catch {
    return
  }
  if (createForm.value.customPassword && createForm.value.password.length < 6) {
    message.warning('密码长度至少 6 位')
    return
  }
  if (!createForm.value.deptId) {
    Modal.confirm({
      title: '未选择所属组织',
      icon: null,
      content: '不选择组织的账号可以登录，但只能看到自己的资源。确定继续创建吗？',
      onOk: () => doSubmitCreate()
    })
    return
  }
  await doSubmitCreate()
}

async function doSubmitCreate() {
  createSaving.value = true
  try {
    const res = await accountApi.create({
      nickname: createForm.value.nickname,
      username: createForm.value.username,
      email: createForm.value.email || undefined,
      deptId: createForm.value.deptId ?? null,
      // 未勾选「自定义密码」时留空，由服务端生成随机初始密码
      password: createForm.value.customPassword ? md5(createForm.value.password) : undefined,
    })
    createDialogVisible.value = false
    credential.value = res.data.data
    credentialVisible.value = true
    await refreshAll()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    createSaving.value = false
  }
}

/** 一次性密码展示弹窗 */
const credentialVisible = ref(false)
const credential = ref<AccountCredentialVO | null>(null)

/** 删除用户（连同账号本身） */
function handleDelete(row: SystemUserVO) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除用户「${row.nickname || row.username}」吗？该账号及其角色绑定都会被清除，此操作不可恢复。若只想移出组织，请使用「移出组织」。`,
    icon: null,
    okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await accountApi.remove([row.id as string])
        message.success('删除成功')
        await refreshAll()
      } catch {
        // 统一由 request.ts 响应拦截器提示
      }
    }
  })
}

</script>

<template>
  <section class="settings-user">
    <header class="settings-user-toolbar">
      <div class="settings-user-filters">
        <AInput
          v-model:value="keyword"
          placeholder="用户名称 / 账号 / 邮箱"
          allow-clear
          style="width: 240px"
          @press-enter="search"
        >
          <template #prefix><SearchOutlined /></template>
        </AInput>
        <AButton type="primary" @click="search">搜索</AButton>
        <AButton @click="resetSearch">重置</AButton>
      </div>
      <div class="settings-user-tools">
        <span class="settings-user-total">共 {{ userList.length }} 条</span>
        <AButton
          v-if="canEdit && departmentId !== null"
          title="把已有账号加入本组织"
          @click="openMemberDialog"
        >
          <template #icon><TeamOutlined /></template>
          添加成员
        </AButton>
        <AButton v-if="canAdd" type="primary" @click="openCreateDialog">
          <template #icon><PlusOutlined /></template>
          新增用户
        </AButton>
        <AButton @click="refreshAll">
          <template #icon><ReloadOutlined /></template>
          刷新
        </AButton>
      </div>
    </header>

    <div v-if="departmentId !== null" class="settings-user-scope">
      <ApartmentOutlined class="settings-user-scope-icon" />
      <span>
        当前组织：<strong>{{ departmentName || '未命名组织' }}</strong>
        <span class="settings-user-muted">（含全部下级部门）</span>
      </span>
      <AButton type="link" size="small" @click="showAll">查看全部账号</AButton>
    </div>

    <AAlert
      v-if="orphanCount > 0"
      type="warning"
      show-icon
      banner
      class="settings-user-alert"
      :message="`有 ${orphanCount} 个账号未归属任何组织，这些账号只能看到自己的资源，建议尽快分配所属组织`"
    />

    <ATable
      v-model:selected-row-keys="selectedRowKeys"
      :columns="columns"
      :data-source="userList"
      :loading="loading"
      :pagination="{ pageSize: 10, showSizeChanger: true, size: 'small' }"
      :scroll="{ x: tableScrollX, y: 'calc(100vh - 285px)' }"
      row-key="id"
      size="middle"
    >
      <template #emptyText>
        <AEmpty :description="emptyText" />
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'user'">
          <div class="settings-user-cell">
            <AAvatar :size="34" class="settings-user-avatar">
              {{ getAvatarText(record) }}
            </AAvatar>
            <div class="settings-user-copy">
              <strong>{{ record.nickname || record.username }}</strong>
              <small>@{{ record.username }}</small>
            </div>
          </div>
        </template>
        <template v-else-if="column.key === 'dept'">
          <div class="settings-user-dept">
            <ATag
              v-if="deptPath(record)"
              :bordered="false"
              color="blue"
              class="settings-user-dept-tag"
              :title="deptPath(record)"
            >
              <ApartmentOutlined />
              {{ deptPath(record) }}
            </ATag>
            <span v-else class="settings-user-muted">未归属组织</span>
            <ATag v-if="record.leader" color="gold" :bordered="false">
              <CrownOutlined />
              负责人
            </ATag>
          </div>
        </template>
        <template v-else-if="column.key === 'email'">
          <span :title="record.email">{{ record.email || '-' }}</span>
        </template>
        <template v-else-if="column.key === 'roles'">
          <template v-if="record.roleNames && record.roleNames.length">
            <ATag
              v-for="name in record.roleNames"
              :key="name"
              :bordered="false"
              color="purple"
              class="settings-user-role-tag"
            >
              <TeamOutlined />
              {{ name }}
            </ATag>
          </template>
          <span v-else class="settings-user-muted">-</span>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <div class="settings-user-status">
            <ATag :color="record.enabled ? 'success' : 'error'" :bordered="false">
              {{ record.enabled ? '正常' : '禁用' }}
            </ATag>
            <ATag v-if="record.mustChangePassword" color="orange" :bordered="false">
              待改密
            </ATag>
          </div>
        </template>
        <template v-else-if="column.key === 'createdAt'">
          {{ record.createdAt || '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="settings-row-actions">
            <AButton
              v-if="canEdit"
              type="link"
              size="small"
              title="设置所属组织"
              @click="openDeptDialog(record)"
            >
              <template #icon><ApartmentOutlined /></template>
            </AButton>
            <AButton
              v-if="canEdit"
              type="link"
              size="small"
              title="分配角色"
              @click="openRoleDialog(record)"
            >
              <template #icon><TeamOutlined /></template>
            </AButton>
            <AButton
              v-if="canEdit && record.deptId"
              type="link"
              size="small"
              title="移出组织"
              @click="handleRemoveFromDept(record)"
            >
              <template #icon><LogoutOutlined /></template>
            </AButton>
            <AButton
              v-if="canResetPwd"
              type="link"
              size="small"
              title="重置密码"
              :disabled="record.id === accountStore.userInfo?.id"
              @click="openPwdDialog(record)"
            >
              <template #icon><KeyOutlined /></template>
            </AButton>
            <AButton
              type="link"
              size="small"
              :title="record.enabled ? '禁用账号' : '激活账号'"
              :danger="record.enabled"
              :disabled="record.id === accountStore.userInfo?.id"
              @click="toggleEnabled(record)"
            >
              <template #icon>
                <StopOutlined v-if="record.enabled" />
                <CheckCircleOutlined v-else />
              </template>
            </AButton>
            <AButton
              v-if="canRemove"
              type="link"
              size="small"
              title="删除账号"
              danger
              :disabled="record.id === accountStore.userInfo?.id"
              @click="handleDelete(record)"
            >
              <template #icon><DeleteOutlined /></template>
            </AButton>
          </div>
        </template>
      </template>
    </ATable>

    <!-- 添加成员 -->
    <AModal
      v-model:open="memberVisible"
      title="添加成员"
      :confirm-loading="memberSaving"
      :width="520"
      @ok="submitMember"
    >
      <p class="settings-dialog-hint">
        把平台上的已有账号分配进「<strong>{{ departmentName || '当前组织' }}</strong>」。
        需要全新账号请用「新增用户」。
      </p>
      <ASelect
        v-model:value="memberDraft"
        :options="memberOptions"
        show-search
        option-filter-prop="label"
        placeholder="请选择账号"
        style="width: 100%"
      />
    </AModal>

    <!-- 设置所属组织 -->
    <AModal
      v-model:open="deptDialogVisible"
      title="设置所属组织"
      :confirm-loading="deptSaving"
      :width="460"
      @ok="submitDept"
    >
      <p class="settings-dialog-hint">
        用户：<strong>{{ deptTarget?.nickname || deptTarget?.username }}</strong>
      </p>
      <ASelect
        v-model:value="deptDraft"
        :options="deptFlatOptions"
        allow-clear
        show-search
        option-filter-prop="label"
        placeholder="请选择组织/部门（留空表示移出组织）"
        style="width: 100%"
      />
      <p class="settings-dialog-note">
        移出组织后账号仍然存在，但不再属于任何组织，数据范围会退化为「仅本人」。
      </p>
    </AModal>

    <!-- 分配角色 -->
    <AModal
      v-model:open="roleDialogVisible"
      title="分配角色"
      :confirm-loading="roleSaving"
      :width="480"
      @ok="submitRoles"
    >
      <p class="settings-dialog-hint">
        用户：<strong>{{ roleTarget?.nickname || roleTarget?.username }}</strong>
        <span v-if="roleTarget?.deptName">（{{ roleTarget.deptName }}）</span>
      </p>
      <ACheckboxGroup v-model:value="roleDraft" class="settings-role-checkbox-group">
        <ACheckbox v-for="role in roleOptions" :key="role.id" :value="role.id">
          {{ role.name }}
          <span class="settings-user-muted">
            （{{ role.code }}{{ role.deptName ? ' · ' + role.deptName : ' · 平台级' }}）
          </span>
        </ACheckbox>
      </ACheckboxGroup>
      <AEmpty v-if="roleOptions.length === 0" description="暂无可分配角色" />
      <p class="settings-dialog-note">
        组织（部门）级角色只能分配给该组织内的账号。
      </p>
    </AModal>

    <!-- 重置密码 -->
    <AModal
      v-model:open="pwdDialogVisible"
      title="重置密码"
      :confirm-loading="pwdSaving"
      :width="460"
      @ok="submitPwd"
    >
      <p class="settings-dialog-hint">
        用户：<strong>{{ pwdTarget?.nickname || pwdTarget?.username }}</strong>
      </p>
      <ACheckbox v-model:checked="pwdCustom">由我指定新密码</ACheckbox>
      <AInputPassword
        v-if="pwdCustom"
        v-model:value="pwdDraft"
        placeholder="请输入新密码（至少 6 位）"
        class="settings-dialog-field"
      />
      <p v-else class="settings-dialog-note">
        将随机生成一个新密码，并在提交后显示一次。
      </p>
      <p class="settings-dialog-note">用户下次登录后需要修改密码。</p>
    </AModal>

    <!-- 新增用户 -->
    <AModal
      v-model:open="createDialogVisible"
      title="新增用户"
      :confirm-loading="createSaving"
      :width="480"
      @ok="submitCreate"
    >
      <p class="settings-dialog-hint">
        新账号首次登录后需要修改密码。所属组织决定它的数据可见范围。
      </p>
      <AForm
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        layout="vertical"
      >
        <AFormItem label="昵称" name="nickname">
          <AInput v-model:value="createForm.nickname" placeholder="请输入昵称" />
        </AFormItem>
        <AFormItem label="登录账号" name="username">
          <AInput v-model:value="createForm.username" placeholder="4-20 个字符，用于登录" />
        </AFormItem>
        <AFormItem label="邮箱" name="email">
          <AInput v-model:value="createForm.email" placeholder="选填，填写后可用于登录" />
        </AFormItem>
        <AFormItem label="所属组织 / 部门">
          <ASelect
            v-model:value="createForm.deptId"
            :options="deptFlatOptions"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="请选择组织（留空表示暂不归属组织）"
          />
        </AFormItem>
        <AFormItem>
          <ACheckbox v-model:checked="createForm.customPassword">由我指定初始密码</ACheckbox>
          <AInputPassword
            v-if="createForm.customPassword"
            v-model:value="createForm.password"
            placeholder="请输入初始密码（至少 6 位）"
            class="settings-dialog-field"
          />
          <p v-else class="settings-dialog-note">
            将随机生成初始密码，并在创建后显示一次。
          </p>
        </AFormItem>
      </AForm>
    </AModal>

    <!-- 一次性密码展示 -->
    <UserCredentialModal v-model:open="credentialVisible" :credential="credential" />
  </section>
</template>

<style scoped lang="scss">
.settings-user {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.settings-user-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  flex-wrap: wrap;
}

.settings-user-filters,
.settings-user-tools {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.settings-user-total {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

// 当前组织范围提示
.settings-user-scope {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
  padding: 6px var(--spacing-sm);
  border-radius: var(--border-radius-md);
  background-color: rgba(15, 116, 255, 0.06);
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);

  strong {
    color: var(--color-text-primary);
  }
}

.settings-user-scope-icon {
  color: var(--color-primary);
}

.settings-user-alert {
  margin-bottom: var(--spacing-sm);
}

.settings-user-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.settings-user-avatar {
  flex-shrink: 0;
  background-color: var(--color-primary);
  font-weight: 600;
}

.settings-user-copy {
  min-width: 0;

  strong {
    display: block;
    color: var(--color-text-primary);
    font-size: var(--font-size-base);
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  small {
    color: var(--color-text-placeholder);
    font-size: var(--font-size-xs);
  }
}

.settings-user-dept {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

// 部门全路径可能很长，超出列宽时省略，完整路径放在 title 里
.settings-user-dept-tag {
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.settings-user-muted {
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.settings-user-role-tag {
  margin-bottom: 2px;
}

.settings-row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: -7px;
}

.settings-dialog-hint {
  margin-bottom: var(--spacing-base);
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);
}

.settings-dialog-field {
  margin-top: var(--spacing-sm);
  width: 100%;
}

.settings-dialog-note {
  margin: var(--spacing-xs) 0 0;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.settings-user-status {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.settings-role-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}
</style>
