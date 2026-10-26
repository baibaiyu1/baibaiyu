/**
 * 系统设置主模态窗组件
 *
 * 左侧分组菜单 + 右侧内容区。由侧边栏「设置」、用户菜单「设置」打开
 * （见 `components/layout/Sidebar.vue`、`components/layout/UserSection.vue`）。
 *
 * 「部门即组织」后，组织架构与成员列表共用一个入口和组织树：
 * 组织资料、成员归属和账号维护集中在「组织与成员」中完成。
 *
 */
<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import SettingsMenu from './SettingsMenu.vue'
import MyAccount from './MyAccount.vue'
import ApiKeys from './ApiKeys.vue'
import StorageManagement from './StorageManagement.vue'
import SystemParams from './SystemParams.vue'
import SystemIntro from './SystemIntro.vue'
import ExecutionNodes from './ExecutionNodes.vue'
import SettingsOrganization from './SettingsOrganization.vue'
import SettingsRoleList from './SettingsRoleList.vue'
import SettingsMenuManage from './SettingsMenuManage.vue'

/**
 * Props定义
 */
const props = defineProps<{
  /** 打开时默认定位的菜单项 */
  defaultMenu?: string
}>()

/** 当前选中的菜单项。兼容旧入口 key，避免外部打开设置时落到空白页。 */
const initialMenu = props.defaultMenu === 'userList' || props.defaultMenu === 'departmentList'
  ? 'organization'
  : props.defaultMenu || 'myAccount'
const currentMenu = ref<string>(initialMenu)

/** 正文区带上标题头，便于在宽屏下定位当前页面 */
const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    myAccount: '我的账户',
    organization: '组织与成员',
    roleList: '角色配置',
    menuManage: '菜单管理',
    apiKeys: 'API Keys',
    storageManagement: '存储管理',
    systemParams: '系统参数',
    executionNodes: '服务监控',
    systemIntro: '系统介绍'
  }
  return titles[currentMenu.value] ?? '系统设置'
})

/** 提供给子组件的上下文 */
provide('currentMenu', currentMenu)

defineExpose({
  currentMenu
})
</script>

<template>
  <div class="settings-container">
    <div class="settings-sidebar">
      <SettingsMenu v-model="currentMenu" />
    </div>
    <div class="settings-divider"></div>
    <div class="settings-content">
      <header class="settings-content-header">
        <h2 class="settings-content-title">{{ pageTitle }}</h2>
      </header>

      <div class="settings-content-body">
        <MyAccount v-if="currentMenu === 'myAccount'" />
        <SettingsOrganization
          v-else-if="currentMenu === 'organization'"
          v-permission="['system:user:list', 'system:dept:list']"
        />
        <SettingsRoleList v-else-if="currentMenu === 'roleList'" v-permission="['system:role:list']" />
        <SettingsMenuManage v-else-if="currentMenu === 'menuManage'" v-permission="['system:menu:list']" />
        <ApiKeys v-else-if="currentMenu === 'apiKeys'" v-permission="['system:apikey:list']" />
        <StorageManagement v-else-if="currentMenu === 'storageManagement'" />
        <SystemParams v-else-if="currentMenu === 'systemParams'" v-permission="['system:params:list']" />
        <ExecutionNodes v-else-if="currentMenu === 'executionNodes'" />
        <SystemIntro v-else-if="currentMenu === 'systemIntro'" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

@media (max-width: 760px) {
  .settings-container {
    flex-direction: column;
  }

  .settings-sidebar {
    width: 100%;
    padding: 8px 16px 0;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .settings-divider {
    display: none;
  }

  .settings-content-header {
    padding: 12px 16px;
  }

  .settings-content-body {
    padding: 0 16px 16px;
    overflow: auto;
  }
}
</style>
