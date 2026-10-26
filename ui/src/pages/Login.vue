<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts" name="LoginPage">
/**
 * 登录页面
 *
 * 系统已关闭自助注册：账号由管理员创建，登录页不再提供注册入口。
 * 「部门即组织」后多组织已下线：一次登录直接进入，不再有「选择组织」视图。
 */
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'
import type { LoginRequest } from '@/types'
import { AuthContainer } from '@/components/auth'
import { useAccountStore } from '@/stores'
import { md5 } from 'js-md5'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { RoutePaths } from '@/router/constants.ts'

interface LoginForm extends LoginRequest {
  remember: boolean
}

const router = useRouter()
const accountStore = useAccountStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const rememberedAccount = localStorage.getItem('remember') === 'true'

const formState = reactive<LoginForm>({
  username: rememberedAccount ? localStorage.getItem('username') || '' : '',
  password: '',
  remember: rememberedAccount,
})

const rules: Record<string, Rule[]> = {
  username: [
    { required: true, message: '请输入账号或邮箱', trigger: 'blur' },
    {
      validator: (_rule, value: string) => {
        if (!value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) || (value.length >= 4 && value.length <= 20)) {
          return Promise.resolve()
        }
        return Promise.reject('请输入4-20个字符的账号或有效邮箱')
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20个字符', trigger: 'blur' },
  ],
}

const handleLogin = async () => {
  try {
    loading.value = true
    await formRef.value?.validate()

    const { remember, ...loginData } = formState
    await accountStore.login({ ...loginData, password: md5(loginData.password) })

    // 管理员新增 / 重置密码的账号：userInfo 上带 mustChangePassword，
    // 进入主界面后由全局弹窗强制修改密码
    completeLogin(remember)
  } catch (error) {
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 完成登录后的跳转
 */
function completeLogin(remember: boolean) {
  message.success('登录成功')
  if (remember) {
    localStorage.setItem('remember', 'true')
    localStorage.setItem('username', formState.username)
  } else {
    localStorage.removeItem('remember')
    localStorage.removeItem('username')
  }
  location.reload()
  setTimeout(() => {
    router.push(RoutePaths.DASHBOARD)
  }, 100)
}
</script>

<template>
  <AuthContainer>
    <div class="view-wrapper">
      <!-- 标题区 -->
      <div class="auth-card-header">
        <h2 class="auth-card-title">账号登录</h2>
        <p class="auth-card-subtitle">欢迎回来，请登录您的账号</p>
      </div>

      <AForm
        ref="formRef"
        name="login"
        class="auth-form"
        :model="formState"
        :rules="rules"
        :required-mark="false"
        layout="vertical"
        @finish="handleLogin"
      >
        <AFormItem label="账号 / 邮箱" name="username" class="auth-form-item">
          <AInput
            v-model:value="formState.username"
            size="large"
            autocomplete="username"
            placeholder="请输入账号或邮箱"
          >
            <template #prefix><UserOutlined /></template>
          </AInput>
        </AFormItem>

        <AFormItem label="密码" name="password" class="auth-form-item">
          <AInputPassword
            v-model:value="formState.password"
            size="large"
            autocomplete="current-password"
            placeholder="请输入密码"
          >
            <template #prefix><LockOutlined /></template>
          </AInputPassword>
        </AFormItem>

        <div class="auth-form-options">
          <ACheckbox v-model:checked="formState.remember">
            记住账号
          </ACheckbox>
          <RouterLink :to="RoutePaths.FORGOT_PASSWORD" class="auth-text-link">
            忘记密码？
          </RouterLink>
        </div>

        <AFormItem class="auth-submit-item">
          <AButton
            type="primary"
            html-type="submit"
            size="large"
            :loading="loading"
            class="auth-submit-btn"
          >
            登录
          </AButton>
        </AFormItem>
      </AForm>

      <!-- 账号由管理员创建，登录页不再提供注册入口 -->
      <div class="auth-footer-link">
        账号由组织管理员统一创建，如需开通请联系管理员
      </div>
    </div>
  </AuthContainer>
</template>

<style scoped lang="scss">
@use '@/styles/modules/auth' as *;

.view-wrapper {
  width: 100%;
}
</style>
