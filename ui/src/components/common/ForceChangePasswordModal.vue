<!-- eslint-disable vue/multi-word-component-names -->
<script setup lang="ts">
/**
 * 首次登录强制修改密码弹窗
 *
 * 管理员新增用户、或管理员重置密码后，账号上会带 `mustChangePassword = true`；
 * 用户登录进入主界面时由本组件弹窗（不可通过「取消 / 遮罩 / ESC」关闭），
 * 修改成功后标记解除，弹窗自动消失。
 *
 * 这里只让用户填新密码：用户刚用管理员分配的密码完成登录，再要求重复输入一遍没有意义，
 * 后端 {@code AccountServiceImpl.changePassword} 在「待改密」状态下同样允许省略旧密码。
 *
 * 挂在主布局 {@code layout/index.vue} 上，因此登录页不需要关心该状态。
 *
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { FormInstance, Rule } from 'ant-design-vue/es/form'
import { LockOutlined } from '@ant-design/icons-vue'
import { md5 } from 'js-md5'
import { useAccountStore } from '@/stores'
import { RoutePaths } from '@/router/constants'

const router = useRouter()
const accountStore = useAccountStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const formState = reactive({
  newPassword: '',
  confirmPassword: '',
})

/** 是否需要强制改密：登录后 userInfo 上带标记即为 true */
const visible = computed(() => accountStore.isLoggedIn && accountStore.mustChangePassword)

const validateConfirmPassword = (_rule: Rule, value: string) => {
  if (value === '') {
    return Promise.reject('请再次输入新密码')
  }
  if (value !== formState.newPassword) {
    return Promise.reject('两次输入的新密码不一致')
  }
  return Promise.resolve()
}

const rules: Record<string, Rule[]> = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20个字符', trigger: 'blur' },
  ],
  confirmPassword: [{ validator: validateConfirmPassword, trigger: 'blur' }],
}

/** 每次弹窗重新打开时清空表单，避免残留上一次的输入 */
watch(visible, (value) => {
  if (value) {
    formState.newPassword = ''
    formState.confirmPassword = ''
    formRef.value?.clearValidate()
  }
})

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await accountStore.changePassword({
      newPassword: md5(formState.newPassword),
    })
    message.success('密码修改成功')
  } catch {
    // 统一由 request.ts 响应拦截器提示
  } finally {
    loading.value = false
  }
}

/**
 * 退出登录，避免用户被困在弹窗里
 * <p>
 * 必须走 router：应用是 hash 路由且部署在 `/web/` 子路径下，
 * 直接 `location.href = '/login'` 会跳到 `http://host/login`（不带 hash、也不在子路径里）而落到 404。
 */
async function handleLogout() {
  try {
    await accountStore.logout()
  } finally {
    await router.replace(RoutePaths.LOGIN)
  }
}
</script>

<template>
  <AModal
    :open="visible"
    title="请修改初始密码"
    :width="440"
    :closable="false"
    :mask-closable="false"
    :keyboard="false"
    :confirm-loading="loading"
    ok-text="确认修改"
    @ok="handleSubmit"
  >
    <p class="force-pwd-hint">
      <LockOutlined />
      当前密码由管理员分配，为了账号安全，请直接设置一个新密码。
    </p>

    <AForm ref="formRef" :model="formState" :rules="rules" layout="vertical">
      <AFormItem label="新密码" name="newPassword">
        <AInputPassword
          v-model:value="formState.newPassword"
          placeholder="6-20 个字符"
          autocomplete="new-password"
        />
      </AFormItem>
      <AFormItem label="确认新密码" name="confirmPassword">
        <AInputPassword
          v-model:value="formState.confirmPassword"
          placeholder="请再次输入新密码"
          autocomplete="new-password"
        />
      </AFormItem>
    </AForm>

    <template #footer>
      <div class="force-pwd-footer">
        <AButton type="link" danger @click="handleLogout">退出登录</AButton>
        <AButton type="primary" :loading="loading" @click="handleSubmit">确认修改</AButton>
      </div>
    </template>
  </AModal>
</template>

<style scoped lang="scss">
.force-pwd-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: var(--spacing-base, 16px);
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--color-warning-bg, #fffbe6);
  color: var(--color-text-regular, #595959);
  font-size: var(--font-size-sm, 13px);
  line-height: 1.6;
}

.force-pwd-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
