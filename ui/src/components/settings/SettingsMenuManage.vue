<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useElementSize } from '@vueuse/core'
import { message, Modal } from 'ant-design-vue'
import type { FormInstance, TreeSelectProps } from 'ant-design-vue'
import {
  CompressOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  ExpandAltOutlined,
  EyeInvisibleOutlined,
  FolderOutlined,
  KeyOutlined,
  MenuOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import * as menuApi from '@/api/menu'
import type { SysMenuType, SysMenuVO } from '@/types/system'
import SettingsTreePanel from './SettingsTreePanel.vue'
import type { SettingsTreeNode } from './treeTypes'

const treeData = ref<SysMenuVO[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const selectedMenuId = ref<string | number | null>(null)
const selectedRowId = ref<string | number | null>(null)
const tableKeyword = ref('')
const menuTypeFilter = ref<'ALL' | SysMenuType>('ALL')
const statusFilter = ref('ALL')
const expandedRowKeys = ref<Array<string | number>>([])
const tableContainer = ref<HTMLElement>()
const { height: tableHeight } = useElementSize(tableContainer)
const tableScrollHeight = computed(() => Math.max(160, tableHeight.value - 56))
let treeRequestId = 0
let initialized = false

const menuTypes = [
  {
    value: 'DIRECTORY',
    label: '目录',
    icon: FolderOutlined,
    description: '用于归类和组织下级菜单。',
  },
  {
    value: 'MENU',
    label: '菜单',
    icon: MenuOutlined,
    description: '配置页面入口、路由和查看权限。',
  },
  {
    value: 'BUTTON',
    label: '按钮权限',
    icon: KeyOutlined,
    description: '定义页面中的操作权限，无需配置路由。',
  },
] as const

const typeMeta = (type?: SysMenuType) =>
  menuTypes.find((item) => item.value === type) ?? menuTypes[1]
const typeColor = { DIRECTORY: 'purple', MENU: 'blue', BUTTON: 'cyan' }

/** 导航、详情和搜索共用完整路径，ID 比较兼容后端的字符串长整型。 */
const menuIndex = computed(() => {
  const index = new Map<string, { node: SysMenuVO; ancestors: SysMenuVO[] }>()
  const walk = (nodes: SysMenuVO[], ancestors: SysMenuVO[]) => {
    for (const node of nodes) {
      index.set(String(node.id), { node, ancestors })
      walk(node.children ?? [], [...ancestors, node])
    }
  }
  walk(treeData.value, [])
  return index
})

const treeNodes = computed<SettingsTreeNode[]>(() => {
  const convert = (nodes: SysMenuVO[]): SettingsTreeNode[] =>
    nodes
      .filter((node) => node.menuType !== 'BUTTON')
      .map((node) => ({
        id: node.id!,
        name: node.name ?? '',
        children: convert(node.children ?? []),
      }))
  return convert(treeData.value)
})

const scopeNode = computed(() =>
  selectedMenuId.value === null ? null : menuIndex.value.get(String(selectedMenuId.value))?.node,
)
const scopeTree = computed(() => (scopeNode.value ? [scopeNode.value] : treeData.value))

/** 平铺行必须移除 children，避免表格再次递归而重复显示节点。 */
function flatten(nodes: SysMenuVO[]): SysMenuVO[] {
  return nodes.flatMap(({ children, ...node }) => [node, ...flatten(children ?? [])])
}

const scopeRows = computed(() => flatten(scopeTree.value))
const typeTabs = computed(() => [
  { value: 'ALL' as const, label: '全部', count: scopeRows.value.length },
  ...menuTypes.map((type) => ({
    ...type,
    count: scopeRows.value.filter((row) => row.menuType === type.value).length,
  })),
])
const isFiltering = computed(
  () =>
    !!tableKeyword.value.trim() || menuTypeFilter.value !== 'ALL' || statusFilter.value !== 'ALL',
)
const filteredRows = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase()
  return scopeRows.value.filter((row) => {
    if (menuTypeFilter.value !== 'ALL' && row.menuType !== menuTypeFilter.value) return false
    if (statusFilter.value === 'ENABLED' && row.status === false) return false
    if (statusFilter.value === 'DISABLED' && row.status !== false) return false
    return (
      !keyword ||
      [row.name, row.perms, row.path, row.component].some((value) =>
        value?.toLowerCase().includes(keyword),
      )
    )
  })
})
const tableData = computed(() => (isFiltering.value ? filteredRows.value : scopeTree.value))
const expandableIds = computed(() =>
  scopeRows.value
    .filter((row) => menuIndex.value.get(String(row.id))?.node.children?.length)
    .map((row) => row.id!),
)
const scopePath = computed(() =>
  selectedMenuId.value === null
    ? '维护页面入口与操作权限'
    : menuIndex.value
        .get(String(selectedMenuId.value))
        ?.ancestors.map((row) => row.name)
        .join(' / ') || '根目录',
)

function parentPath(id?: string | number) {
  return (
    menuIndex.value
      .get(String(id))
      ?.ancestors.map((row) => row.name)
      .join(' / ') || '根目录'
  )
}

function resetFilters() {
  tableKeyword.value = ''
  menuTypeFilter.value = 'ALL'
  statusFilter.value = 'ALL'
}

function expandScope() {
  expandedRowKeys.value = [...expandableIds.value]
}

watch(selectedMenuId, () => {
  expandedRowKeys.value = scopeTree.value.map((row) => row.id!)
})

const detailOpen = ref(false)
const detail = ref<SysMenuVO | null>(null)
const detailLoading = ref(false)
const detailFailed = ref(false)
const buttonKeyword = ref('')
let detailRequestId = 0

const detailButtons = computed(() => {
  const keyword = buttonKeyword.value.trim().toLowerCase()
  return (detail.value?.buttons ?? []).filter(
    (button) =>
      !keyword ||
      [button.name, button.perms].some((value) => value?.toLowerCase().includes(keyword)),
  )
})

async function loadDetail(id: string | number) {
  const requestId = ++detailRequestId
  detail.value = null
  detailLoading.value = true
  detailFailed.value = false
  try {
    const res = await menuApi.detail(id)
    if (requestId === detailRequestId) detail.value = res.data.data ?? null
  } catch {
    if (requestId === detailRequestId) detailFailed.value = true
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false
  }
}

function openDetail(row: SysMenuVO) {
  if (row.id === undefined || row.id === null) return
  selectedRowId.value = row.id
  buttonKeyword.value = ''
  detailOpen.value = true
  void loadDetail(row.id)
}

watch(detailOpen, (open) => {
  if (!open) {
    detailRequestId++
    detail.value = null
    selectedRowId.value = null
    detailLoading.value = false
  }
})

watch(filteredRows, (rows) => {
  if (
    selectedRowId.value !== null &&
    !rows.some((row) => String(row.id) === String(selectedRowId.value))
  ) {
    detailOpen.value = false
  }
})

async function load() {
  const requestId = ++treeRequestId
  loading.value = true
  loadFailed.value = false
  try {
    const res = await menuApi.tree()
    if (requestId !== treeRequestId) return
    treeData.value = res.data.data ?? []
    if (selectedMenuId.value !== null && !menuIndex.value.has(String(selectedMenuId.value)))
      selectedMenuId.value = null
    expandedRowKeys.value = initialized
      ? expandedRowKeys.value.filter((id) => menuIndex.value.has(String(id)))
      : treeData.value.map((row) => row.id!)
    initialized = true
    if (
      detailOpen.value &&
      selectedRowId.value !== null &&
      menuIndex.value.has(String(selectedRowId.value))
    ) {
      await loadDetail(selectedRowId.value)
    }
  } catch {
    if (requestId === treeRequestId) loadFailed.value = true
  } finally {
    if (requestId === treeRequestId) loading.value = false
  }
}

const columns = [
  { title: '菜单名称', dataIndex: 'name', key: 'name', width: 224 },
  { title: '权限字符', dataIndex: 'perms', key: 'perms', width: 230 },
  { title: '路由 / 组件', key: 'route', width: 200 },
  { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 64, align: 'center' as const },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'action', width: 136, fixed: 'right' as const },
]

async function copyPermission(perms?: string) {
  if (!perms) return
  try {
    await navigator.clipboard.writeText(perms)
    message.success('权限字符已复制')
  } catch {
    message.warning('复制失败，请选中权限字符手动复制')
  }
}

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

function emptyForm(parentId: string | number = 0, menuType: SysMenuType = 'MENU') {
  return {
    id: undefined as string | number | undefined,
    parentId,
    name: '',
    menuType,
    path: '',
    component: '',
    perms: '',
    icon: '',
    sortOrder: 0,
    visible: true,
    cached: true,
    external: false,
    status: true,
    remark: '',
  }
}

const form = ref(emptyForm())
const dialogTitle = computed(
  () => `${form.value.id === undefined ? '新增' : '编辑'}${typeMeta(form.value.menuType).label}`,
)
const formRules = computed(() => ({
  name: [
    {
      required: true,
      whitespace: true,
      message: `请输入${typeMeta(form.value.menuType).label}名称`,
      trigger: 'blur',
    },
  ],
  perms: [
    {
      required: form.value.menuType === 'BUTTON',
      whitespace: true,
      message: '请输入权限字符',
      trigger: 'blur',
    },
  ],
}))

/** 父节点保留层级，并排除按钮、自身及全部子孙。 */
const parentOptions = computed(() => {
  const excluded = new Set<string>()
  const current = menuIndex.value.get(String(form.value.id))?.node
  if (current) flatten([current]).forEach((node) => excluded.add(String(node.id)))
  const convert = (nodes: SysMenuVO[]): NonNullable<TreeSelectProps['treeData']> =>
    nodes
      .filter((node) => node.menuType !== 'BUTTON' && !excluded.has(String(node.id)))
      .map((node) => ({
        value: node.id!,
        title: node.name ?? '',
        children: convert(node.children ?? []),
      }))
  return [{ value: 0, title: '根目录', children: convert(treeData.value) }]
})

async function openCreate(
  parentId: string | number = selectedMenuId.value ?? 0,
  menuType: SysMenuType = 'MENU',
) {
  form.value = emptyForm(parentId, menuType)
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

async function openEdit(row: SysMenuVO) {
  form.value = {
    id: row.id,
    parentId: row.parentId ?? 0,
    name: row.name ?? '',
    menuType: row.menuType ?? 'MENU',
    path: row.path ?? '',
    component: row.component ?? '',
    perms: row.perms ?? '',
    icon: row.icon ?? '',
    sortOrder: row.sortOrder ?? 0,
    visible: row.visible !== false,
    cached: row.cached !== false,
    external: row.external === true,
    status: row.status !== false,
    remark: row.remark ?? '',
  }
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

async function submit() {
  if (saving.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const { id, ...values } = form.value
    const payload = {
      ...values,
      name: values.name.trim(),
      perms: values.perms.trim(),
      path: values.path.trim(),
      component: values.component.trim(),
      icon: values.icon.trim(),
      remark: values.remark.trim(),
    }
    if (id !== undefined) await menuApi.update(id, payload)
    else await menuApi.save(payload)
    message.success(id !== undefined ? '已保存修改' : '创建成功')
    dialogVisible.value = false
    await load()
  } catch {
    // 请求拦截器统一提示；保留表单内容供重试。
  } finally {
    saving.value = false
  }
}

function handleRemove(row: SysMenuVO) {
  Modal.confirm({
    title: `删除${typeMeta(row.menuType).label}`,
    content: `确定删除「${row.name}」吗？存在下级菜单或按钮权限时无法删除。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await menuApi.remove(row.id!)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="settings-menu-manage">
    <SettingsTreePanel
      title="菜单结构"
      all-label="全部菜单"
      :nodes="treeNodes"
      :selected="selectedMenuId"
      :loading="loading"
      :refreshable="false"
      @select="(id) => (selectedMenuId = id)"
    />

    <section class="settings-menu-main" aria-label="菜单管理列表">
      <header class="menu-context">
        <div class="menu-context-title">
          <span class="menu-context-icon"
            ><FolderOutlined v-if="scopeNode" /><MenuOutlined v-else
          /></span>
          <div>
            <h3>{{ scopeNode?.name || '全部菜单' }}</h3>
            <p :title="scopePath">{{ scopePath }}</p>
          </div>
        </div>
        <div class="menu-header-actions">
          <AButton :loading="loading" title="刷新菜单" aria-label="刷新菜单" @click="load">
            <template #icon><ReloadOutlined /></template>
          </AButton>
          <AButton v-permission="['system:menu:add']" type="primary" @click="openCreate()">
            <template #icon><PlusOutlined /></template>
            {{ scopeNode ? '新增下级' : '新增菜单' }}
          </AButton>
        </div>
      </header>

      <div class="menu-type-tabs" role="tablist" aria-label="菜单类型">
        <button
          v-for="tab in typeTabs"
          :key="tab.value"
          type="button"
          role="tab"
          :aria-selected="menuTypeFilter === tab.value"
          :class="{ active: menuTypeFilter === tab.value }"
          @click="menuTypeFilter = tab.value"
        >
          {{ tab.label }}<span>{{ tab.count }}</span>
        </button>
      </div>

      <div class="menu-filters">
        <AInput
          v-model:value="tableKeyword"
          allow-clear
          placeholder="搜索名称、权限字符或路径"
          aria-label="搜索菜单"
          class="menu-search"
        >
          <template #prefix><SearchOutlined /></template>
        </AInput>
        <ASelect
          v-model:value="statusFilter"
          aria-label="筛选菜单状态"
          class="menu-status-filter"
          :options="[
            { value: 'ALL', label: '全部状态' },
            { value: 'ENABLED', label: '已启用' },
            { value: 'DISABLED', label: '已停用' },
          ]"
        />
        <AButton v-if="isFiltering" type="link" @click="resetFilters">重置筛选</AButton>
        <div v-else class="menu-expand-actions">
          <AButton
            type="text"
            size="small"
            title="展开全部"
            aria-label="展开全部"
            :disabled="!expandableIds.length"
            @click="expandScope"
          >
            <template #icon><ExpandAltOutlined /></template>
          </AButton>
          <AButton
            type="text"
            size="small"
            title="收起全部"
            aria-label="收起全部"
            :disabled="!expandedRowKeys.length"
            @click="expandedRowKeys = []"
          >
            <template #icon><CompressOutlined /></template>
          </AButton>
        </div>
      </div>

      <AAlert
        v-if="loadFailed"
        type="error"
        show-icon
        message="菜单加载失败，请刷新重试"
        class="menu-load-error"
      />

      <div ref="tableContainer" class="menu-table-container">
        <ATable
          v-model:expanded-row-keys="expandedRowKeys"
          :columns="columns"
          :data-source="tableData"
          :loading="loading"
          :pagination="false"
          :scroll="{ x: 954, y: tableScrollHeight }"
          :indent-size="16"
          row-key="id"
          size="middle"
          :row-class-name="
            (row: SysMenuVO) =>
              String(row.id) === String(selectedRowId) ? 'menu-row-selected' : ''
          "
          :custom-row="(row: SysMenuVO) => ({ onClick: () => openDetail(row) })"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <div class="menu-name-cell">
                <button
                  class="menu-name"
                  type="button"
                  :title="record.name"
                  @click.stop="openDetail(record)"
                >
                  <component
                    :is="typeMeta(record.menuType).icon"
                    :class="['menu-type-icon', `is-${record.menuType?.toLowerCase()}`]"
                  />
                  <span>{{ record.name }}</span>
                </button>
                <span v-if="isFiltering" class="menu-parent-path" :title="parentPath(record.id)">{{
                  parentPath(record.id)
                }}</span>
              </div>
            </template>
            <template v-else-if="column.key === 'perms'">
              <div v-if="record.perms" class="menu-permission-cell">
                <code class="menu-permission" :title="record.perms">{{ record.perms }}</code>
                <AButton
                  type="text"
                  size="small"
                  class="menu-copy"
                  title="复制权限字符"
                  aria-label="复制权限字符"
                  @click.stop="copyPermission(record.perms)"
                >
                  <template #icon><CopyOutlined /></template>
                </AButton>
              </div>
              <span v-else class="menu-muted">—</span>
            </template>
            <template v-else-if="column.key === 'route'">
              <div v-if="record.menuType !== 'BUTTON'" class="menu-route-cell">
                <span :title="record.path">{{ record.path || '未配置路由' }}</span>
                <small v-if="record.component" :title="record.component">{{
                  record.component
                }}</small>
              </div>
              <span v-else class="menu-muted">操作权限</span>
            </template>
            <template v-else-if="column.key === 'sortOrder'">{{ record.sortOrder ?? 0 }}</template>
            <template v-else-if="column.key === 'status'">
              <div class="menu-state-cell">
                <span class="menu-state" :class="{ disabled: record.status === false }"
                  ><i />{{ record.status === false ? '已停用' : '已启用' }}</span
                >
                <small v-if="record.visible === false && record.menuType !== 'BUTTON'"
                  ><EyeInvisibleOutlined /> 菜单隐藏</small
                >
              </div>
            </template>
            <template v-else-if="column.key === 'action'">
              <div class="menu-row-actions" @click.stop>
                <AButton type="link" size="small" @click="openDetail(record)">详情</AButton>
                <AButton
                  v-if="record.menuType !== 'BUTTON'"
                  v-permission="['system:menu:add']"
                  type="text"
                  size="small"
                  title="新增下级"
                  aria-label="新增下级"
                  @click="openCreate(record.id)"
                >
                  <template #icon><PlusOutlined /></template>
                </AButton>
                <AButton
                  v-permission="['system:menu:edit']"
                  type="text"
                  size="small"
                  title="编辑"
                  aria-label="编辑"
                  @click="openEdit(record)"
                >
                  <template #icon><EditOutlined /></template>
                </AButton>
                <AButton
                  v-permission="['system:menu:remove']"
                  type="text"
                  size="small"
                  danger
                  title="删除"
                  aria-label="删除"
                  @click="handleRemove(record)"
                >
                  <template #icon><DeleteOutlined /></template>
                </AButton>
              </div>
            </template>
          </template>
          <template #emptyText>
            <div class="menu-table-empty">
              <SearchOutlined />
              <strong>{{
                loadFailed ? '暂时无法加载菜单' : isFiltering ? '没有找到匹配的菜单' : '暂无菜单'
              }}</strong>
              <p>
                {{
                  isFiltering
                    ? '试试其他关键词，或清除筛选条件。'
                    : '创建目录、菜单或按钮权限，建立清晰的菜单结构。'
                }}
              </p>
              <AButton v-if="isFiltering" @click="resetFilters">清除筛选</AButton>
              <AButton v-else-if="loadFailed" @click="load">重新加载</AButton>
              <AButton
                v-else
                v-permission="['system:menu:add']"
                type="primary"
                @click="openCreate()"
                >新增菜单</AButton
              >
            </div>
          </template>
        </ATable>
      </div>
      <footer class="menu-table-footer">
        <span>{{
          isFiltering
            ? `匹配 ${filteredRows.length} / ${scopeRows.length} 个节点`
            : `共 ${scopeRows.length} 个节点`
        }}</span>
        <span><FolderOutlined /> 目录 <MenuOutlined /> 菜单 <KeyOutlined /> 按钮权限</span>
      </footer>
    </section>

    <ADrawer
      v-model:open="detailOpen"
      title="菜单详情"
      width="min(480px, 100%)"
      :get-container="false"
      :root-style="{ position: 'absolute' }"
      :body-style="{ padding: '20px' }"
    >
      <AppSpin :spinning="detailLoading">
        <template v-if="detail">
          <div class="menu-detail-summary">
            <span class="menu-detail-icon"><component :is="typeMeta(detail.menuType).icon" /></span>
            <div>
              <h3>{{ detail.name }}</h3>
              <p>{{ parentPath(detail.id) }}</p>
            </div>
          </div>
          <div class="menu-detail-meta">
            <ATag :color="typeColor[detail.menuType || 'MENU']" :bordered="false">{{
              typeMeta(detail.menuType).label
            }}</ATag>
            <span class="menu-state" :class="{ disabled: detail.status === false }"
              ><i />{{ detail.status === false ? '已停用' : '已启用' }}</span
            >
            <AButton
              v-permission="['system:menu:edit']"
              size="small"
              class="menu-detail-edit"
              @click="openEdit(detail)"
            >
              <template #icon><EditOutlined /></template>编辑
            </AButton>
          </div>

          <section class="menu-detail-section">
            <h4>基本信息</h4>
            <dl class="menu-detail-grid">
              <div>
                <dt>权限字符</dt>
                <dd class="menu-detail-permission">
                  <code>{{ detail.perms || '—' }}</code
                  ><AButton
                    v-if="detail.perms"
                    type="text"
                    size="small"
                    title="复制权限字符"
                    aria-label="复制权限字符"
                    @click="copyPermission(detail.perms)"
                    ><template #icon><CopyOutlined /></template
                  ></AButton>
                </dd>
              </div>
              <template v-if="detail.menuType !== 'BUTTON'">
                <div>
                  <dt>路由地址</dt>
                  <dd>{{ detail.path || '—' }}</dd>
                </div>
                <div>
                  <dt>组件路径</dt>
                  <dd>{{ detail.component || '—' }}</dd>
                </div>
                <div>
                  <dt>菜单图标</dt>
                  <dd>{{ detail.icon || '—' }}</dd>
                </div>
                <div>
                  <dt>显示状态</dt>
                  <dd>{{ detail.visible === false ? '隐藏' : '显示' }}</dd>
                </div>
                <div>
                  <dt>页面缓存</dt>
                  <dd>{{ detail.cached === false ? '不缓存' : '缓存' }}</dd>
                </div>
                <div>
                  <dt>外部链接</dt>
                  <dd>{{ detail.external ? '是' : '否' }}</dd>
                </div>
              </template>
              <div>
                <dt>显示顺序</dt>
                <dd>{{ detail.sortOrder ?? 0 }}</dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd>{{ detail.createdAt || '—' }}</dd>
              </div>
              <div v-if="detail.remark">
                <dt>备注</dt>
                <dd class="menu-detail-remark">{{ detail.remark }}</dd>
              </div>
            </dl>
          </section>

          <section v-if="detail.menuType !== 'BUTTON'" class="menu-detail-section">
            <header class="menu-section-header">
              <h4>
                按钮权限 <span>{{ detail.buttons?.length ?? 0 }}</span>
              </h4>
              <AButton
                v-permission="['system:menu:add']"
                type="link"
                size="small"
                @click="openCreate(detail.id, 'BUTTON')"
                ><template #icon><PlusOutlined /></template>新增权限</AButton
              >
            </header>
            <AInput
              v-if="detail.buttons?.length"
              v-model:value="buttonKeyword"
              allow-clear
              size="small"
              placeholder="搜索按钮名称或权限字符"
              aria-label="搜索按钮权限"
              class="menu-button-search"
              ><template #prefix><SearchOutlined /></template
            ></AInput>
            <ul v-if="detailButtons.length" class="menu-button-list">
              <li v-for="button in detailButtons" :key="button.id">
                <div class="menu-button-head">
                  <span class="menu-button-name">{{ button.name }}</span>
                  <span v-if="button.status === false" class="menu-muted">已停用</span>
                  <div class="menu-button-actions">
                    <AButton
                      v-permission="['system:menu:edit']"
                      type="text"
                      size="small"
                      title="编辑按钮权限"
                      aria-label="编辑按钮权限"
                      @click="openEdit(button)"
                      ><template #icon><EditOutlined /></template
                    ></AButton>
                    <AButton
                      v-permission="['system:menu:remove']"
                      type="text"
                      size="small"
                      danger
                      title="删除按钮权限"
                      aria-label="删除按钮权限"
                      @click="handleRemove(button)"
                      ><template #icon><DeleteOutlined /></template
                    ></AButton>
                  </div>
                </div>
                <div class="menu-detail-permission">
                  <code>{{ button.perms || '未配置权限字符' }}</code
                  ><AButton
                    v-if="button.perms"
                    type="text"
                    size="small"
                    title="复制权限字符"
                    aria-label="复制权限字符"
                    @click="copyPermission(button.perms)"
                    ><template #icon><CopyOutlined /></template
                  ></AButton>
                </div>
              </li>
            </ul>
            <p v-else class="menu-detail-empty">
              {{ buttonKeyword ? '没有匹配的按钮权限' : '暂未配置按钮权限' }}
            </p>
          </section>
        </template>
        <div v-else class="menu-detail-empty">
          <p>
            {{
              detailLoading
                ? '正在加载菜单详情…'
                : detailFailed
                  ? '菜单详情加载失败'
                  : '菜单不存在或已被删除'
            }}
          </p>
          <AButton v-if="detailFailed && selectedRowId !== null" @click="loadDetail(selectedRowId)"
            >重新加载</AButton
          >
        </div>
      </AppSpin>
    </ADrawer>

    <AModal
      v-model:open="dialogVisible"
      :title="dialogTitle"
      :confirm-loading="saving"
      :width="680"
      :ok-text="form.id === undefined ? '创建' : '保存修改'"
      cancel-text="取消"
      :mask-closable="!saving"
      :closable="!saving"
      :keyboard="!saving"
      :cancel-button-props="{ disabled: saving }"
      :destroy-on-close="true"
      @ok="submit"
    >
      <AForm
        ref="formRef"
        :model="form"
        :rules="formRules"
        layout="vertical"
        :disabled="saving"
        class="menu-form"
      >
        <div class="menu-form-types">
          <ARadioGroup v-model:value="form.menuType" aria-label="菜单类型" button-style="solid">
            <ARadioButton v-for="item in menuTypes" :key="item.value" :value="item.value"
              ><component :is="item.icon" /> {{ item.label }}</ARadioButton
            >
          </ARadioGroup>
          <p>{{ typeMeta(form.menuType).description }}</p>
        </div>
        <AFormItem label="上级菜单" name="parentId">
          <ATreeSelect
            v-model:value="form.parentId"
            :tree-data="parentOptions"
            show-search
            tree-default-expand-all
            tree-node-filter-prop="title"
            placeholder="选择上级菜单"
            :dropdown-style="{ maxHeight: '320px', overflow: 'auto' }"
          />
        </AFormItem>
        <ARow :gutter="16">
          <ACol :xs="24" :sm="18"
            ><AFormItem :label="`${typeMeta(form.menuType).label}名称`" name="name"
              ><AInput
                v-model:value="form.name"
                placeholder="输入清晰易辨的名称"
                :maxlength="100"
                show-count /></AFormItem
          ></ACol>
          <ACol :xs="24" :sm="6"
            ><AFormItem label="显示顺序" name="sortOrder"
              ><AInputNumber
                v-model:value="form.sortOrder"
                :min="0"
                :precision="0"
                style="width: 100%" /></AFormItem
          ></ACol>
        </ARow>
        <AFormItem
          label="权限字符"
          name="perms"
          :extra="
            form.menuType === 'BUTTON'
              ? '角色授予该权限后，才可执行对应操作。'
              : '与页面查看权限对应，例如 system:menu:list。'
          "
        >
          <AInput
            v-model:value="form.perms"
            :placeholder="
              form.menuType === 'BUTTON' ? '例如：system:menu:add' : '例如：system:menu:list'
            "
            :maxlength="150"
            class="menu-mono-input"
          />
        </AFormItem>
        <template v-if="form.menuType !== 'BUTTON'">
          <div class="menu-form-section-title">页面配置</div>
          <ARow :gutter="16">
            <ACol :xs="24" :sm="12"
              ><AFormItem label="路由地址" name="path"
                ><AInput
                  v-model:value="form.path"
                  placeholder="例如：settings/menu"
                  :maxlength="200" /></AFormItem
            ></ACol>
            <ACol :xs="24" :sm="12"
              ><AFormItem label="菜单图标" name="icon"
                ><AInput
                  v-model:value="form.icon"
                  placeholder="例如：MenuOutlined"
                  :maxlength="100" /></AFormItem
            ></ACol>
          </ARow>
          <AFormItem v-if="form.menuType === 'MENU'" label="组件路径" name="component"
            ><AInput
              v-model:value="form.component"
              placeholder="例如：settings/Menu"
              :maxlength="200"
          /></AFormItem>
        </template>
        <div class="menu-form-options">
          <div>
            <div>
              <strong>启用{{ typeMeta(form.menuType).label }}</strong
              ><small>停用后，该节点的权限不再生效</small>
            </div>
            <ASwitch v-model:checked="form.status" aria-label="启用状态" />
          </div>
          <div v-if="form.menuType !== 'BUTTON'">
            <div><strong>在菜单中显示</strong><small>控制导航入口是否可见</small></div>
            <ASwitch v-model:checked="form.visible" aria-label="菜单显示状态" />
          </div>
          <template v-if="form.menuType === 'MENU'">
            <div>
              <div><strong>缓存页面</strong><small>切换页面时保留已打开的页面状态</small></div>
              <ASwitch v-model:checked="form.cached" aria-label="页面缓存" />
            </div>
            <div>
              <div><strong>外部链接</strong><small>将路由地址作为外部链接打开</small></div>
              <ASwitch v-model:checked="form.external" aria-label="外部链接" />
            </div>
          </template>
        </div>
        <AFormItem label="备注" name="remark"
          ><ATextarea
            v-model:value="form.remark"
            :auto-size="{ minRows: 2, maxRows: 4 }"
            placeholder="补充菜单用途或权限说明（选填）"
            :maxlength="500"
            show-count
        /></AFormItem>
      </AForm>
    </AModal>
  </div>
</template>

<style scoped lang="scss">
.settings-menu-manage {
  position: relative;
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--color-text-primary);

  :deep(.settings-tree-panel) {
    width: 208px;
  }
  :deep(.settings-tree-header) {
    padding-top: 12px;
  }
}

.settings-menu-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding-left: 24px;
}

.menu-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0 20px;
}
.menu-context-title,
.menu-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.menu-context-title {
  min-width: 0;
  > div {
    min-width: 0;
  }
  h3 {
    margin: 0;
    font-size: 16px;
    line-height: 24px;
    font-weight: 600;
  }
  p {
    margin: 2px 0 0;
    font-size: 12px;
    color: var(--color-text-secondary);
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
.menu-context-icon,
.menu-detail-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  color: var(--color-primary);
  background: var(--color-bg-base, #f5f7fa);
  font-size: 19px;
}
.menu-header-actions {
  flex-shrink: 0;
  gap: 8px;
}
.menu-type-tabs {
  display: flex;
  gap: 24px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--color-border-light);
  button {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 0 0 12px;
    border: 0;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    background: none;
    color: var(--color-text-secondary);
    font-size: 13px;
    white-space: nowrap;
    cursor: pointer;
    span {
      padding: 0 6px;
      min-width: 22px;
      border-radius: 5px;
      background: var(--color-bg-base, #f5f7fa);
      font-size: 11px;
      line-height: 18px;
      text-align: center;
    }
    &:hover,
    &.active {
      color: var(--color-primary);
    }
    &.active {
      border-bottom-color: var(--color-primary);
      font-weight: 600;
    }
    &:focus-visible {
      outline: 2px solid var(--color-primary);
      outline-offset: 3px;
      border-radius: 3px;
    }
  }
}
.menu-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  padding: 16px 0;
}
.menu-search {
  flex: 1;
  max-width: 360px;
  min-width: 120px;
}
.menu-status-filter {
  width: 114px;
  flex-shrink: 0;
}
.menu-expand-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}
.menu-load-error {
  margin-bottom: 12px;
}
.menu-table-container {
  flex: 1;
  min-height: 0;
  min-width: 0;
  :deep(.ant-table-thead > tr > th) {
    font-size: 12px;
    color: var(--color-text-secondary);
    font-weight: 500;
    background: var(--color-bg-base, #f7f8fa);
  }
  :deep(.ant-table-tbody > tr > td) {
    padding: 12px 10px;
    font-size: 13px;
  }
  :deep(.ant-table-tbody > tr) {
    cursor: pointer;
  }
  :deep(.ant-table-tbody > tr.menu-row-selected > td) {
    background: var(--ant-color-primary-bg, #e6f4ff);
  }
  :deep(.ant-table-row-expand-icon) {
    margin-top: 4px;
  }
}
.menu-name-cell {
  display: inline-flex;
  flex-direction: column;
  max-width: calc(100% - 24px);
  vertical-align: middle;
}
.menu-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
  span:last-child {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  &:hover {
    color: var(--color-primary);
  }
  &:focus-visible {
    outline: 2px solid var(--color-primary);
    outline-offset: 3px;
  }
}
.menu-type-icon {
  flex-shrink: 0;
  color: var(--color-text-secondary);
  &.is-directory {
    color: #c69238;
  }
  &.is-menu {
    color: var(--color-primary);
  }
  &.is-button {
    color: #28a09b;
  }
}
.menu-parent-path {
  margin-top: 4px;
  padding-left: 22px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--color-text-secondary);
}
.menu-permission-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 2px;
}
.menu-permission {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  font-family: var(--font-family-mono, monospace);
  color: var(--color-text-regular);
}
.menu-copy {
  flex-shrink: 0;
  color: var(--color-text-secondary);
  opacity: 0;
}
.menu-permission-cell:hover .menu-copy,
.menu-permission-cell:focus-within .menu-copy {
  opacity: 1;
}
.menu-route-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  span,
  small {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  small {
    font-size: 11px;
    color: var(--color-text-secondary);
  }
}
.menu-state-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  small {
    font-size: 11px;
    color: var(--color-text-secondary);
  }
}
.menu-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  white-space: nowrap;
  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #36ad6a;
  }
  &.disabled {
    color: var(--color-text-secondary);
    i {
      background: var(--color-text-placeholder);
    }
  }
}
.menu-muted {
  color: var(--color-text-placeholder);
  font-size: 12px;
}
.menu-row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  white-space: nowrap;
  :deep(.ant-btn) {
    padding-inline: 4px;
  }
}
.menu-table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  padding-top: 12px;
  font-size: 11px;
  color: var(--color-text-secondary);
  > span:last-child {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    .anticon:not(:first-child) {
      margin-left: 8px;
    }
  }
}
.menu-table-empty {
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 48px 16px;
  > .anticon {
    font-size: 26px;
    margin-bottom: 14px;
    color: var(--color-text-placeholder);
  }
  strong {
    font-size: 14px;
    font-weight: 500;
  }
  p {
    margin: 8px 0 18px;
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}
.menu-detail-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  h3 {
    margin: 0;
    font-size: 18px;
    overflow-wrap: anywhere;
  }
  p {
    margin: 5px 0 0;
    font-size: 12px;
    color: var(--color-text-secondary);
    overflow-wrap: anywhere;
  }
}
.menu-detail-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.menu-detail-edit {
  margin-left: auto;
}
.menu-detail-section {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border-light);
  h4 {
    margin: 0 0 16px;
    font-size: 13px;
    font-weight: 600;
  }
}
.menu-detail-grid {
  margin: 0;
  > div {
    display: grid;
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 12px;
    padding: 7px 0;
  }
  dt,
  dd {
    margin: 0;
    font-size: 12px;
    line-height: 22px;
    overflow-wrap: anywhere;
  }
  dt {
    color: var(--color-text-secondary);
  }
}
.menu-detail-permission {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
  code {
    font-family: var(--font-family-mono, monospace);
    font-size: 12px;
    line-height: 24px;
    overflow-wrap: anywhere;
  }
  :deep(.ant-btn) {
    flex-shrink: 0;
    color: var(--color-text-secondary);
  }
}
.menu-detail-remark {
  white-space: pre-wrap;
}
.menu-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  h4 {
    margin: 0;
    span {
      color: var(--color-text-secondary);
      font-weight: 400;
      margin-left: 4px;
    }
  }
}
.menu-button-search {
  margin-bottom: 12px;
}
.menu-button-list {
  list-style: none;
  margin: 0;
  padding: 0;
  li {
    padding: 10px 12px;
    border: 1px solid var(--color-border-light);
    border-radius: 8px;
    + li {
      margin-top: 8px;
    }
  }
}
.menu-button-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.menu-button-name {
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.menu-button-actions {
  display: flex;
  margin-left: auto;
  flex-shrink: 0;
}
.menu-detail-empty {
  padding: 28px 8px;
  color: var(--color-text-secondary);
  font-size: 12px;
  text-align: center;
}
.menu-form {
  padding-top: 16px;
  :deep(.ant-form-item) {
    margin-bottom: 18px;
  }
  :deep(.ant-form-item-label > label) {
    font-size: 13px;
  }
  :deep(.ant-form-item-extra) {
    font-size: 12px;
    padding-top: 4px;
  }
}
.menu-form-types {
  margin-bottom: 20px;
  :deep(.ant-radio-group) {
    display: flex;
  }
  :deep(.ant-radio-button-wrapper) {
    flex: 1;
    text-align: center;
  }
  p {
    margin: 9px 0 0;
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}
.menu-form-section-title {
  margin: 24px 0 16px;
  padding-top: 18px;
  border-top: 1px solid var(--color-border-light);
  font-size: 13px;
  font-weight: 600;
}
.menu-mono-input {
  font-family: var(--font-family-mono, monospace);
}
.menu-form-options {
  margin: 4px 0 20px;
  padding: 0 14px;
  background: var(--color-bg-base, #f7f8fa);
  border-radius: 8px;
  > div {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    padding: 12px 0;
    + div {
      border-top: 1px solid var(--color-border-light);
    }
  }
  strong,
  small {
    display: block;
  }
  strong {
    font-size: 13px;
    font-weight: 500;
  }
  small {
    margin-top: 3px;
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}

@media (max-width: 1100px) {
  .settings-menu-manage :deep(.settings-tree-panel) {
    width: 180px;
  }
  .settings-menu-main {
    padding-left: 16px;
  }
  .menu-type-tabs {
    gap: 18px;
  }
  .menu-filters {
    gap: 8px;
    flex-wrap: wrap;
  }
  .menu-table-footer > span:last-child {
    display: none;
  }
}
@media (max-width: 760px) {
  .settings-menu-manage {
    flex-direction: column;
    min-height: 560px;
  }
  .settings-menu-manage :deep(.settings-tree-panel) {
    width: 100%;
    height: 160px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border-light);
  }
  .settings-menu-main {
    padding: 12px 0 0;
  }
  .menu-context {
    padding-bottom: 16px;
    gap: 10px;
  }
  .menu-context-icon {
    display: none;
  }
  .menu-context-title h3 {
    font-size: 14px;
  }
  .menu-type-tabs {
    gap: 0;
    justify-content: space-between;
    button {
      font-size: 12px;
      gap: 4px;
    }
  }
  .menu-table-empty {
    padding: 24px 8px;
  }
}
</style>
