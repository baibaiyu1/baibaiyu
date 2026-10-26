/**
 * 系统设置左侧菜单组件 — 按分组罗列账户、组织、权限、基础设施等设置项
 *
 */
<script setup lang="ts">
import { computed, type Component } from 'vue'
import {
  ApartmentOutlined,
  CloudServerOutlined,
  ControlOutlined,
  DatabaseOutlined,
  InfoCircleOutlined,
  KeyOutlined,
  MenuOutlined,
  SafetyCertificateOutlined,
  UserOutlined
} from '@ant-design/icons-vue'
import { useAccountStore } from '@/stores'

defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const accountStore = useAccountStore()
const isAdmin = computed(() => accountStore.isAdmin)

interface SettingsMenuItem {
  key: string
  label: string
  icon: Component
  /** 所属分组 */
  group: string
  /** 是否仅管理员可见 */
  adminOnly?: boolean
}

const menuItems: SettingsMenuItem[] = [
  { key: 'myAccount', label: '我的账户', icon: UserOutlined, group: '账户' },
  { key: 'organization', label: '组织与成员', icon: ApartmentOutlined, group: '组织', adminOnly: true },
  { key: 'roleList', label: '角色配置', icon: SafetyCertificateOutlined, group: '权限', adminOnly: true },
  { key: 'menuManage', label: '菜单管理', icon: MenuOutlined, group: '权限', adminOnly: true },
  { key: 'apiKeys', label: 'API Keys', icon: KeyOutlined, group: '配置', adminOnly: true },
  { key: 'storageManagement', label: '存储管理', icon: DatabaseOutlined, group: '配置' },
  { key: 'systemParams', label: '系统参数', icon: ControlOutlined, group: '配置', adminOnly: true },
  { key: 'executionNodes', label: '服务监控', icon: CloudServerOutlined, group: '监控', adminOnly: true },
  { key: 'systemIntro', label: '系统介绍', icon: InfoCircleOutlined, group: '关于' }
]

/** 按权限过滤后按分组聚合 */
const menuGroups = computed(() => {
  const visible = menuItems.filter((item) => !item.adminOnly || isAdmin.value)
  const groups: Array<{ name: string; items: SettingsMenuItem[] }> = []
  for (const item of visible) {
    const last = groups[groups.length - 1]
    if (last && last.name === item.group) {
      last.items.push(item)
    } else {
      groups.push({ name: item.group, items: [item] })
    }
  }
  return groups
})

function handleMenuClick(key: string) {
  emit('update:modelValue', key)
}
</script>

<template>
  <div class="settings-menu">
    <div class="settings-menu-title">系统设置</div>
    <div class="settings-menu-list">
      <section v-for="group in menuGroups" :key="group.name" class="settings-menu-group">
        <div class="settings-menu-group-label">{{ group.name }}</div>
        <div
          v-for="item in group.items"
          :key="item.key"
          class="settings-menu-item"
          :class="{ active: modelValue === item.key }"
          @click="handleMenuClick(item.key)"
        >
          <component :is="item.icon" class="settings-menu-icon" />
          <span class="settings-menu-label">{{ item.label }}</span>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

.settings-menu-group + .settings-menu-group {
  margin-top: var(--spacing-base);
}

.settings-menu-group-label {
  padding: 4px 12px;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
  letter-spacing: 0.4px;
}

@media (max-width: 760px) {
  .settings-menu-title,
  .settings-menu-group-label {
    display: none;
  }

  .settings-menu-list,
  .settings-menu-group {
    display: flex;
    flex-direction: row;
    width: max-content;
  }

  .settings-menu-group + .settings-menu-group {
    margin-top: 0;
  }

  .settings-menu-item {
    white-space: nowrap;
  }
}
</style>
