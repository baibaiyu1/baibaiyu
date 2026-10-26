<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 主布局组件 - 左右结构
 *
 */
import { computed } from 'vue';
import { useRoute } from 'vue-router';
const route = useRoute();
import { useAccountStore } from "@/stores";
import { Sidebar } from '@/components/layout'
import ForceChangePasswordModal from '@/components/common/ForceChangePasswordModal.vue'
const { getRefresh } =  useAccountStore()

const isRefresh = computed(() => {
  return getRefresh()
})
</script>

<template>
  <div class="app-layout flex">
    <!-- 左侧侧边栏 -->
    <Sidebar />

    <!-- 右侧内容区域 -->
    <main class="layout-content flex-1">
      <router-view
        v-slot="{ Component }"
        :key="route.path + isRefresh"
      >
        <transition name="slide-right" mode="out-in" appear>
          <div style="height: 100%; position: relative;">
            <component :is="Component" />
          </div>
        </transition>
      </router-view>
    </main>

    <!-- 管理员新增/重置密码的账号：首次登录强制修改密码 -->
    <ForceChangePasswordModal />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/layout' as *;
</style>
