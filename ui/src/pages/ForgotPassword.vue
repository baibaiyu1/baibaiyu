<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts" name="ForgotPasswordPage">
import { AuthContainer } from '@/components/auth'
import { RoutePaths } from '@/router/constants'
</script>

<template>
  <AuthContainer>
    <div class="auth-card-header">
      <h2 class="auth-card-title">找回密码</h2>
      <p class="auth-card-subtitle">请联系所在组织的管理员重置密码</p>
    </div>

    <ol class="password-help">
      <li>向管理员提供您的账号或邮箱。</li>
      <li>使用管理员重置后的临时密码登录。</li>
      <li>按照登录后的提示设置新密码。</li>
    </ol>

    <RouterLink :to="RoutePaths.LOGIN" custom v-slot="{ navigate, href }">
      <AButton type="primary" size="large" :href="href" class="auth-submit-btn" @click="navigate">
        返回登录
      </AButton>
    </RouterLink>
  </AuthContainer>
</template>

<style scoped lang="scss">
@use '@/styles/modules/auth' as *;

.password-help {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 0 0 28px;
  padding-left: 20px;
  color: #667085;
  font-size: 14px;
  line-height: 1.8;

  li::marker {
    color: #4f6ef7;
    font-weight: 600;
  }
}
</style>
