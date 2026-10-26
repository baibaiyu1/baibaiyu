/**
 * 系统设置 - 组织（部门）弹窗
 *
 * 一个弹窗承担三种形态，避免组织维护入口分散：
 * - `detail`：只读查看组织资料（负责人 / 成员数 / 联系人 / 描述），底部提供「编辑资料」「删除组织」；
 * - `create`：在指定上级组织下新建组织；
 * - `edit`：修改组织资料与负责人。
 *
 * 负责人候选只取「本组织及其下级组织的账号 + 无组织账号」，
 * 编辑时把已在任的负责人补进候选，避免历史负责人被误显示成「账号 xx」。
 *
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import * as departmentApi from '@/api/department'
import * as systemUserApi from '@/api/systemUser'
import { useAccountStore } from '@/stores'
import type { Department, DepartmentVO, SystemUserVO } from '@/types/system'

const props = withDefaults(defineProps<{
  open: boolean
  /** 弹窗形态：查看详情 / 新建 / 编辑 */
  mode?: 'detail' | 'create' | 'edit'
  /** 目标组织（detail / edit 必填） */
  department?: DepartmentVO | null
  /** 新建时的上级组织ID（0 或空表示顶级组织） */
  parentId?: string | number | null
  /** 组织树，用于上级组织下拉与详情里的上级名称 */
  departments?: DepartmentVO[]
}>(), {
  mode: 'create',
  department: null,
  parentId: null,
  departments: () => []
})

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  /** 保存成功（新建时带上新组织，便于父级定位） */
  (e: 'saved', department?: Department): void
  /** 删除成功 */
  (e: 'removed', id: string | number): void
  /** 从详情切到编辑 */
  (e: 'edit'): void
}>()

const accountStore = useAccountStore()

/** 是否拥有组织编辑 / 删除权限 */
const canEdit = computed(() => accountStore.hasPerm('system:dept:edit'))
const canRemove = computed(() => accountStore.hasPerm('system:dept:remove'))

const saving = ref(false)
const form = ref<Department>({})

const isDetail = computed(() => props.mode === 'detail')
const dialogTitle = computed(() => {
  if (props.mode === 'detail') return '组织详情'
  if (props.mode === 'edit') return '编辑组织'
  return props.parentId ? '新增下级组织' : '新增组织'
})

/** 上级组织名称（详情展示用） */
const parentName = computed(() => {
  const parentId = props.department?.parentId
  if (parentId === undefined || parentId === null || Number(parentId) === 0) {
    return '顶级组织'
  }
  return findDepartment(props.departments, parentId)?.name ?? `组织 ${parentId}`
})

/** 上级组织下拉选项（排除自身及其子孙，避免把组织挂到自己下面） */
const parentOptions = computed(() => {
  const exclude = new Set<string | number>()
  const selfId = form.value.id
  if (selfId !== undefined && selfId !== null) {
    const self = findDepartment(props.departments, selfId)
    if (self) {
      collectAll(self, exclude)
    }
  }
  const options: Array<{ value: string | number; label: string }> = [
    { value: 0, label: '顶级组织' }
  ]
  const push = (nodes: DepartmentVO[], depth: number) => {
    for (const node of nodes) {
      if (exclude.has(node.id as string | number)) {
        continue
      }
      options.push({
        value: node.id as string | number,
        label: `${'　'.repeat(depth)}${node.name ?? ''}`
      })
      if (node.children?.length) {
        push(node.children, depth + 1)
      }
    }
  }
  push(props.departments, 1)
  return options
})

/** 负责人候选账号（可多选） */
const leaderOptions = ref<Array<{ value: string | number; label: string }>>([])

function findDepartment(nodes: DepartmentVO[], id: string | number): DepartmentVO | null {
  for (const node of nodes) {
    if (String(node.id) === String(id)) {
      return node
    }
    const hit = node.children?.length ? findDepartment(node.children, id) : null
    if (hit) {
      return hit
    }
  }
  return null
}

function collectAll(node: DepartmentVO, acc: Set<string | number>) {
  acc.add(node.id as string | number)
  for (const child of node.children ?? []) {
    collectAll(child, acc)
  }
}

async function loadLeaderOptions(deptId?: string | number | null, current?: Array<string | number>) {
  try {
    const res = await systemUserApi.list(deptId ? { deptId } : undefined)
    const users = res.data.data ?? []
    const options = users.map((user: SystemUserVO) => ({
      value: user.id,
      label: `${user.nickname || user.username}（@${user.username}）`
    }))
    const known = new Set(options.map((item) => String(item.value)))
    for (const id of current ?? []) {
      if (!known.has(String(id))) {
        // 兜底：库里的历史负责人可能已不在可见范围内，仍要能展示出 ID
        options.push({ value: id, label: `账号 ${id}` })
      }
    }
    leaderOptions.value = options
  } catch {
    leaderOptions.value = []
  }
}

/** 打开时按形态初始化表单 */
watch(
  () => [props.open, props.mode, props.department, props.parentId] as const,
  () => {
    if (!props.open) {
      return
    }
    if (props.mode === 'create') {
      form.value = {
        parentId: props.parentId ?? 0,
        sortOrder: 0,
        enabled: true,
        leaderAccountIds: []
      }
      void loadLeaderOptions(props.parentId ?? null)
      return
    }
    const target = props.department
    if (!target) {
      return
    }
    const currentLeaders = [...(target.leaderAccountIds ?? [])]
    form.value = {
      id: target.id,
      parentId: target.parentId ?? 0,
      name: target.name,
      code: target.code,
      leader: target.leader,
      leaderAccountIds: currentLeaders,
      phone: target.phone,
      email: target.email,
      sortOrder: target.sortOrder ?? 0,
      description: target.description,
      contactName: target.contactName,
      contactEmail: target.contactEmail,
      remark: target.remark,
      enabled: target.enabled !== false
    }
    if (props.mode === 'edit') {
      void loadLeaderOptions(target.id, currentLeaders)
    }
  },
  { immediate: true }
)

function close() {
  emit('update:open', false)
}

function toEdit() {
  emit('edit')
}

async function submit() {
  if (!form.value.name || !form.value.name.trim()) {
    message.warning('请输入组织（部门）名称')
    return
  }
  saving.value = true
  try {
    if (form.value.id !== undefined && form.value.id !== null) {
      await departmentApi.update(form.value.id, { ...form.value })
      message.success('组织已更新')
      emit('saved', { ...form.value })
    } else {
      const res = await departmentApi.save({ ...form.value })
      message.success('组织创建成功')
      emit('saved', res.data.data ?? { ...form.value })
    }
    close()
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    saving.value = false
  }
}

function handleRemove() {
  const target = props.department
  if (!target) {
    return
  }
  Modal.confirm({
    title: '确认删除',
    icon: null,
    content: `确定要删除组织「${target.name}」吗？存在下级组织或已分配用户时无法删除。`,
    okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await departmentApi.remove(target.id as string | number)
        message.success('组织已删除')
        emit('removed', target.id as string | number)
        close()
      } catch {
        // 统一由 request.ts 响应拦截器提示
      }
    }
  })
}
</script>

<template>
  <AModal
    :open="props.open"
    :title="dialogTitle"
    :width="isDetail ? 620 : 580"
    :confirm-loading="saving"
    @update:open="emit('update:open', $event)"
    @ok="submit"
  >
    <!-- 详情：只读展示组织资料 -->
    <template v-if="isDetail">
      <ADescriptions :column="2" size="small" bordered>
        <ADescriptionsItem label="组织名称">{{ props.department?.name || '-' }}</ADescriptionsItem>
        <ADescriptionsItem label="组织编码">{{ props.department?.code || '-' }}</ADescriptionsItem>
        <ADescriptionsItem label="上级组织">{{ parentName }}</ADescriptionsItem>
        <ADescriptionsItem label="状态">
          <ATag :color="props.department?.enabled === false ? 'default' : 'success'" :bordered="false">
            {{ props.department?.enabled === false ? '停用' : '正常' }}
          </ATag>
        </ADescriptionsItem>
        <ADescriptionsItem label="负责人">
          <template v-if="props.department?.leaderNames?.length">
            <ATag
              v-for="name in props.department.leaderNames"
              :key="name"
              color="gold"
              :bordered="false"
              class="settings-org-leader-tag"
            >
              {{ name }}
            </ATag>
          </template>
          <span v-else>{{ props.department?.leader || '-' }}</span>
        </ADescriptionsItem>
        <ADescriptionsItem label="成员数">
          {{ props.department?.memberCount ?? 0 }} 人
          <span class="settings-org-detail-muted">
            （含下级，本组织 {{ props.department?.userCount ?? 0 }} 人）
          </span>
        </ADescriptionsItem>
        <ADescriptionsItem label="联系人">{{ props.department?.contactName || '-' }}</ADescriptionsItem>
        <ADescriptionsItem label="联系人邮箱">{{ props.department?.contactEmail || '-' }}</ADescriptionsItem>
        <ADescriptionsItem label="创建时间" :span="2">
          {{ props.department?.createdAt || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="组织描述" :span="2">
          {{ props.department?.description || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="备注" :span="2">{{ props.department?.remark || '-' }}</ADescriptionsItem>
      </ADescriptions>
    </template>

    <!-- 新建 / 编辑：组织资料表单 -->
    <AForm v-else layout="vertical" :model="form">
      <AFormItem label="上级组织 / 部门">
        <ASelect v-model:value="form.parentId" :options="parentOptions" placeholder="请选择上级组织" />
      </AFormItem>
      <ARow :gutter="16">
        <ACol :span="14">
          <AFormItem label="组织（部门）名称" required>
            <AInput v-model:value="form.name" placeholder="请输入名称" :maxlength="100" />
          </AFormItem>
        </ACol>
        <ACol :span="10">
          <AFormItem label="组织编码">
            <AInput v-model:value="form.code" placeholder="全局唯一，可留空" :maxlength="100" />
          </AFormItem>
        </ACol>
      </ARow>
      <ARow :gutter="16">
        <ACol :span="14">
          <AFormItem label="负责人账号（可多选）">
            <ASelect
              v-model:value="form.leaderAccountIds"
              :options="leaderOptions"
              mode="multiple"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="从成员中选择，可多选（可留空）"
              :max-tag-count="3"
            />
          </AFormItem>
        </ACol>
        <ACol :span="10">
          <AFormItem label="显示顺序">
            <AInputNumber v-model:value="form.sortOrder" :min="0" style="width: 100%" />
          </AFormItem>
        </ACol>
      </ARow>
      <ARow :gutter="16">
        <ACol :span="12">
          <AFormItem label="联系人">
            <AInput v-model:value="form.contactName" placeholder="选填" :maxlength="50" />
          </AFormItem>
        </ACol>
        <ACol :span="12">
          <AFormItem label="联系人邮箱">
            <AInput v-model:value="form.contactEmail" placeholder="选填" :maxlength="100" />
          </AFormItem>
        </ACol>
      </ARow>
      <AFormItem label="组织描述">
        <ATextarea
          v-model:value="form.description"
          :rows="2"
          placeholder="选填，描述该组织的职责范围"
          :maxlength="500"
        />
      </AFormItem>
      <AFormItem label="状态">
        <ARadioGroup v-model:value="form.enabled">
          <ARadio :value="true">正常</ARadio>
          <ARadio :value="false">停用</ARadio>
        </ARadioGroup>
      </AFormItem>
      <AFormItem label="备注">
        <ATextarea v-model:value="form.remark" :rows="2" placeholder="选填" :maxlength="500" />
      </AFormItem>
    </AForm>

    <!-- 详情：底部操作 -->
    <template v-if="isDetail" #footer>
      <div class="settings-org-detail-footer">
        <AButton v-if="canRemove" danger type="text" @click="handleRemove">删除组织</AButton>
        <span class="settings-org-detail-spacer"></span>
        <AButton @click="close">关闭</AButton>
        <AButton v-if="canEdit" type="primary" @click="toEdit">编辑资料</AButton>
      </div>
    </template>
  </AModal>
</template>

<style scoped lang="scss">
.settings-org-leader-tag {
  margin-bottom: 2px;
}

.settings-org-detail-muted {
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.settings-org-detail-footer {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
}

.settings-org-detail-spacer {
  flex: 1;
}
</style>
