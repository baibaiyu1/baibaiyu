import request from '@/utils/request'
import setting from "@/config/setting";
import type { ApiResponse } from '@/types'
import type {
  LoginRequest,
  LoginResponse,
  ChangePasswordRequest,
  UpdateProfileRequest
} from '@/types'

/**
 * 用户登录
 * POST /auth/login
 */
export function login(data: LoginRequest) {
  return request.post<ApiResponse<LoginResponse>>('/api/auth/login', data)
}

/**
 * 刷新Token
 * POST /auth/refresh-token
 */
export function refreshToken(refreshToken: string) {
  return request.post<ApiResponse<LoginResponse>>('/api/auth/refresh-token', { refreshToken }, {
    headers: {
      [setting.refreshTokenRequest]: true
    }
  })
}

/**
 * 用户退出
 * POST /auth/logout
 */
export function logout() {
  return request.post<ApiResponse<void>>('/api/auth/logout')
}

/**
 * 当前登录账号在当前租户下的权限点（单轨权限）
 * GET /auth/perms
 */
export function perms() {
  return request.get<ApiResponse<string[]>>('/api/auth/perms')
}

/**
 * 修改密码
 * POST /auth/change-password
 */
export function changePassword(data: ChangePasswordRequest) {
  return request.post<ApiResponse<boolean>>('/api/auth/change-password', data)
}

/**
 * 修改个人信息
 * POST /auth/update-profile
 */
export function updateProfile(data: UpdateProfileRequest) {
  return request.post<ApiResponse<boolean>>('/api/auth/update-profile', data)
}

/**
 * 通过ChatKey换取Token
 * POST /auth/chat-key-token/{chatKey}
 */
export function chatKeyToken(chatKey: string) {
  return request.post<ApiResponse<LoginResponse>>(`/api/auth/chat-key-token/${chatKey}`, undefined, {
    headers: { token: false }
  })
}
