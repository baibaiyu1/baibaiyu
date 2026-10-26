import 'ant-design-vue/dist/reset.css';
import 'highlight.js/styles/github.min.css';
import 'katex/dist/katex.min.css';
import './styles/markdown.scss';
import './assets/main.css'
import './assets/iconfont/iconfont.css'


import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import Antd from 'ant-design-vue';
import { message } from 'ant-design-vue'

// dayjs 并设置为中文
import * as dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
dayjs.locale('zh-cn');

import App from './App.vue'
import router from './router'
import { useAccountStore } from '@/stores'
import AppModal from '@/components/common/AppModal.vue'
import AppSpin from '@/components/common/AppSpin.vue'

const app = createApp(App)

const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)
app.use(router)
app.use(Antd)
app.component('AppModal', AppModal)
app.component('AppSpin', AppSpin)
message.config({
  top: '50px',
})


// 自定义权限指令（单轨权限：只认「角色 → 菜单/按钮 perms」权限点）
// 用法：v-permission="['agent:definition:edit']"，命中任意一个即显示；
// 权限点由 accountStore.loadPerms() 从 GET /auth/perms 拉取，不再使用租户角色旧枚举。
// 注意：权限点是异步拉取的（登录/刷新页面），因此指令要监听权限点变化后重新判定，
//       否则刷新页面时会因为权限点还没到而把按钮永久隐藏。
app.directive('permission', {
  mounted(el, binding) {
    const requiredPerms: string[] = binding.value || []
    if (!requiredPerms.length) {
      return
    }

    const accountStore = useAccountStore()
    const apply = (allowed: boolean) => {
      if (allowed) {
        el.style.display = ''
        el.style.visibility = ''
        el.style.height = ''
        el.style.width = ''
        el.style.overflow = ''
      } else {
        el.style.display = 'none'
        el.style.visibility = 'hidden'
        el.style.height = '0'
        el.style.width = '0'
        el.style.overflow = 'hidden'
      }
    }

    const stop = watch(
      () => accountStore.perms,
      (perms: string[]) => apply(requiredPerms.some((perm) => (perms || []).includes(perm))),
      { immediate: true, deep: true }
    )
    ;(el as HTMLElement & { __permStop?: () => void }).__permStop = stop
  },
  unmounted(el) {
    ;(el as HTMLElement & { __permStop?: () => void }).__permStop?.()
  }
});

// 刷新页面时先用本地缓存的权限点渲染，同时后台再拉一次，保证角色/菜单变更后无需重新登录
const accountStore = useAccountStore()
if (accountStore.isLoggedIn) {
  void accountStore.loadPerms()
}

app.mount('#app')
