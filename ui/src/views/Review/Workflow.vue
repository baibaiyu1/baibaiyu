<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 审查工作流 —— 工作流发布审批工作台
 *
 */
import ApprovalWorkbench from '@/components/approval/ApprovalWorkbench.vue'
</script>

<template>
  <ApprovalWorkbench
    title="审查工作流"
    description="工作流发布审批：提交申请 → 审批人处理 → 通过后工作流置为已发布"
    resource-type="WORKFLOW"
  />
</template>
