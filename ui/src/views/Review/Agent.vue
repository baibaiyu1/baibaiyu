<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 审查智能体 —— 智能体发布审批工作台
 *
 */
import ApprovalWorkbench from '@/components/approval/ApprovalWorkbench.vue'
</script>

<template>
  <ApprovalWorkbench
    title="审查智能体"
    description="智能体发布审批：提交申请 → 审批人处理 → 通过后智能体启用并发布生效"
    resource-type="AGENT"
  />
</template>
