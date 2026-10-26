/**
 * 认证相关路由
 *
 */

import type { AppRouteRecordRaw } from '../types'
import { RouteNames, RoutePaths } from '../constants'

/**
 * 认证路由配置
 */
const authRoutes: AppRouteRecordRaw[] = [
  {
    path: RoutePaths.LOGIN,
    name: RouteNames.LOGIN,
    component: () => import('@/pages/Login.vue'),
    meta: {
      title: '登录',
      hidden: true,
    },
  },
  {
    path: RoutePaths.FORGOT_PASSWORD,
    name: RouteNames.FORGOT_PASSWORD,
    component: () => import('@/pages/ForgotPassword.vue'),
    meta: {
      title: '忘记密码',
      hidden: true,
    },
  },
]

export default authRoutes
