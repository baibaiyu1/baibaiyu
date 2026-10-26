<script setup lang="ts">
/**
 * 初始/重置密码结果弹窗
 *
 * 服务端只保存加盐摘要，明文密码仅在创建/重置的响应中返回一次，
 * 因此这里需要把密码显式展示给管理员，并提供复制。
 *
 */
import { message } from 'ant-design-vue'
import { CopyOutlined, ExclamationCircleOutlined } from '@ant-design/icons-vue'
import type { AccountCredentialVO } from '@/types'

const props = defineProps<{
  open: boolean
  credential: AccountCredentialVO | null
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

async function copyPassword() {
  const password = props.credential?.password
  if (!password) {
    message.warning('该密码由你自行指定，服务端未保留明文')
    return
  }
  try {
    await navigator.clipboard.writeText(password)
    message.success('密码已复制')
  } catch {
    message.warning('浏览器拒绝了复制操作，请手动选中复制')
  }
}

function handleClose() {
  emit('update:open', false)
}
</script>

<template>
  <AModal
    :open="open"
    title="账号密码"
    :width="460"
    :mask-closable="false"
    ok-text="我已保存"
    @ok="handleClose"
    @cancel="handleClose"
  >
    <AAlert
      v-if="credential?.generated"
      type="warning"
      show-icon
      message="密码仅在此处显示一次，关闭后无法再次查看"
      description="请立即复制并转交给该用户；用户首次登录后需要自行修改密码。"
      class="credential-alert"
    />
    <AAlert
      v-else
      type="info"
      show-icon
      message="已使用你指定的密码"
      description="服务端只保存密码摘要，无法再次查看；请自行把密码告知该用户。"
      class="credential-alert"
    />

    <div v-if="credential" class="credential-body">
      <div class="credential-row">
        <span class="credential-label">登录账号</span>
        <span class="credential-value">{{ credential.username }}</span>
      </div>
      <div v-if="credential.generated" class="credential-row">
        <span class="credential-label">随机密码</span>
        <span class="credential-value credential-password">
          <code>{{ credential.password }}</code>
          <AButton type="link" size="small" @click="copyPassword">
            <template #icon><CopyOutlined /></template>
            复制
          </AButton>
        </span>
      </div>
      <p class="credential-tip">
        <ExclamationCircleOutlined />
        用户下次登录后会被要求修改密码。
      </p>
    </div>
  </AModal>
</template>

<style scoped lang="scss">
.credential-alert {
  margin-bottom: var(--spacing-base, 16px);
}

.credential-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm, 8px);
}

.credential-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm, 8px);
  font-size: var(--font-size-base, 14px);
}

.credential-label {
  flex: 0 0 68px;
  color: var(--color-text-secondary, #8c8c8c);
}

.credential-value {
  flex: 1;
  min-width: 0;
  color: var(--color-text-primary, #262626);
}

.credential-password {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs, 4px);

  code {
    flex: 1;
    min-width: 0;
    padding: 2px 8px;
    border-radius: 4px;
    background: var(--color-fill-quaternary, #f5f5f5);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 15px;
    letter-spacing: 0.5px;
    word-break: break-all;
  }
}

.credential-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: var(--spacing-xs, 4px) 0 0;
  color: var(--color-text-secondary, #8c8c8c);
  font-size: var(--font-size-xs, 12px);
}
</style>
