<script setup lang="ts">
/**
 * 系统介绍组件
 *
 */
import { BRAND_NAME } from '@/config/brand'
</script>

<template>
  <div class="system-intro-container">
    <h1 class="system-intro-title">
      {{ BRAND_NAME }}
    </h1>

    <p class="system-intro-paragraph">
      <strong>{{ BRAND_NAME }}</strong> 面向企业的智能体应用建设，提供从资源配置、应用编排到发布运行的统一管理入口。
    </p>

    <p class="system-intro-paragraph">
      通过<strong>模型供应商、提示词、工具、技能、知识库和 MCP 服务</strong>配置智能体能力，使用<strong>工作流</strong>编排业务流程，并通过对话、定时任务或 API 调用应用。
    </p>

    <p class="system-intro-paragraph">
      对话支持<strong>流式回复、文件上传、工具审批、图片与产物预览</strong>，可结合交互式表单和图表呈现执行结果。会话历史与文件工作区统一管理，便于查看和追踪任务过程。
    </p>

    <p class="system-intro-paragraph">
      管理员通过<strong>组织与成员、角色权限和资源授权</strong>管理访问范围；智能体、工具、技能、知识库与 MCP 服务可按业务需要设置可见范围和使用授权。
    </p>

  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;
</style>
