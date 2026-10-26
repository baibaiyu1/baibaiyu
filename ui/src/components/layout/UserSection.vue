<script setup lang="ts">
/**
 * 用户信息区域组件
 *
 * 「部门即组织」后多组织已下线：不再有租户（组织）切换器与「管理组织」入口，
 * 账号的组织归属在「我的账户」里查看，组织维护走「设置 → 组织与部门」。
 *
 */
import { computed, h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RoutePaths } from '@/router/constants.ts'
import { useAccountStore } from '@/stores'
import {
  ApartmentOutlined,
  LogoutOutlined,
  SettingOutlined,
  BookOutlined
} from '@ant-design/icons-vue'
import { Modal } from 'ant-design-vue'
import SettingsModal from '@/components/settings/SettingsModal.vue'

const router = useRouter()
const { logout, userInfo } = useAccountStore()

/** 系统设置模态窗显示状态 */
const settingsVisible = ref(false)

/**
 * 用户名首字母(用于头像)
 */
const avatarText = computed(() => {
  if (!userInfo?.username) return '?'
  return userInfo.username.charAt(0).toUpperCase()
})

/**
 * 已登录账号的组织（部门）名称
 */
const deptName = computed(() => userInfo?.deptName || '未归属组织')

/**
 * 下拉菜单项
 */
const menuItems = [
  {
    key: 'doc',
    label: '文档',
    icon: () => h(BookOutlined),
  },
  {
    type: 'divider',
  },
  {
    key: 'profile',
    label: '设置',
    icon: () => h(SettingOutlined),
  },
  {
    type: 'divider',
  },
  {
    key: 'logout',
    danger: true,
    label: '退出',
    icon: () => h(LogoutOutlined),
  }
]

/**
 * 处理菜单点击
 */
const handleMenuClick = async ({ key }: { key: string }) => {
  if (key === 'logout') {
    Modal.confirm({
      title: '确认',
      icon: null,
      content: '确认退出当前系统,是否继续?',
      onOk: async () => {
        await logout()
        await router.push(RoutePaths.LOGIN)
      }
    })
  } else if (key === 'profile') {
    settingsVisible.value = true
  } else if (key === 'doc') {
    openMarkdownDoc()
  }
}

const openMarkdownDoc = () => {
  window.open('/doc.html#/', '_blank')
}
</script>

<template>
  <div class="user-section flex items-center gap-sm pr-md">
    <!-- 组织归属展示（多组织切换已下线，只展示当前所属组织） -->
    <div class="user-dept flex items-center gap-xs" :title="deptName">
      <ApartmentOutlined />
      <span class="dept-name">{{ deptName }}</span>
    </div>

    <ADropdown :trigger="['hover']">
      <div class="user-info flex items-center gap-sm">
        <AAvatar :size="32" style="background-color: var(--color-primary)">
          {{ avatarText }}
        </AAvatar>
      </div>

      <template #overlay>
        <AMenu :items="menuItems" @click="handleMenuClick" />
      </template>
    </ADropdown>

    <!-- 系统设置模态窗 -->
    <AModal
      v-model:open="settingsVisible"
      wrap-class-name="full-modal"
      :footer="null"
      :destroyOnClose="true"
      :width="'100%'"
    >
      <SettingsModal />
    </AModal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/components/user-section' as *;

.user-dept {
  max-width: 180px;
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-secondary, #595959);
  background: var(--fill-quaternary, #f5f5f5);
}

.dept-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 150px;
}
</style>
