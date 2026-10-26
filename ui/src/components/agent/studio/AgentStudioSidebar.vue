<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 智能体编排工作台 —— 左侧导航
 *
 * 结构参考「青云Agent平台」：返回 + 智能体名片 + 分区导航。
 * 名片本身可点击 —— 亮出的就是当前智能体的头像/名称/描述，点开即「基础设置」（Agent 设置弹窗）。
 *
 * @component
 */
import type { Component } from 'vue'
import { ArrowLeftOutlined, EditOutlined } from '@ant-design/icons-vue'
import { AGENT_AVATAR } from '@/components/agent/studio/avatar'

defineProps<{
  /** 智能体名称 */
  agentName: string
  /** 智能体描述 */
  agentDescription?: string
  /** 智能体类型：CUSTOM / A2A */
  agentType?: string
  /** 是否启用 */
  enabled?: boolean
  /** 当前分区 */
  activeSection: string
  /** 分区列表 */
  items: Array<{ key: string; label: string; icon: Component }>
}>()

const emit = defineEmits<{
  /** 切换分区、返回或打开智能体编辑 */
  select: [key: string]
}>()
</script>

<template>
  <aside class="agstudio-sidebar">
    <div class="agstudio-sidebar__brand">
      <button class="agstudio-sidebar__back" title="返回智能体列表" @click="emit('select', 'back')">
        <ArrowLeftOutlined />
      </button>
      <div>
        <strong>智能体编排</strong>
        <small>Agent Studio</small>
      </div>
    </div>

    <!-- 智能体名片：头像 + 名称 + 描述，整卡可点，进入基础设置 -->
    <div
      class="agstudio-profile"
      role="button"
      tabindex="0"
      title="智能体编辑（基础信息 / 安全策略 / 高级配置）"
      @click="emit('select', 'edit')"
      @keydown.enter="emit('select', 'edit')"
    >
      <div class="agstudio-profile__head">
        <img class="agstudio-profile__avatar" :src="AGENT_AVATAR" :alt="agentName" />
        <button
          class="agstudio-profile__edit"
          title="智能体编辑"
          @click.stop="emit('select', 'edit')"
        >
          <EditOutlined />
        </button>
      </div>
      <strong :title="agentName">{{ agentName }}</strong>
      <p>{{ agentDescription || '暂无描述' }}</p>
      <span
        class="agstudio-profile__status"
        :class="enabled ? 'is-on' : 'is-off'"
      >{{ enabled ? '已启用' : '已停用' }}</span>
    </div>

    <nav class="agstudio-nav">
      <button
        v-for="item in items"
        :key="item.key"
        class="agstudio-nav__item"
        :class="{ 'is-active': activeSection === item.key }"
        @click="emit('select', item.key)"
      >
        <component :is="item.icon" />
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="agstudio-sidebar__footer">
      {{ agentType === 'A2A' ? 'A2A 远端智能体' : '自定义智能体' }}
    </div>
  </aside>
</template>

<style scoped lang="scss">
@use '@/styles/agent/studio-sidebar.scss' as *;
</style>
