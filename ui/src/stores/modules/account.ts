import { useResourceSpaceStore } from './resourceSpace'
/**
 * 账号Store
 *
 * 「部门即组织」后多组织（多租户）已下线：
 * - 组织归属完全由账号的 `deptId` 决定，登录不再需要「选择/切换组织」；
 * - `account_tenant.role` 旧枚举已废弃，鉴权一律走权限点（`perms`）。
 *
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AccountVO, TenantInfo } from '@/types'
import * as authApi from '@/api/auth'
import * as accountApi from '@/api/account'
import type {
  LoginRequest,
  LoginResponse,
  ChangePasswordRequest,
  UpdateProfileRequest,
} from '@/types'
import {
  getToken,
  setToken,
  removeToken,
  getRefreshToken,
  setRefreshToken,
  removeRefreshToken,
  getUser,
  setUser,
  removeUser,
} from '@/utils/auth'
import cache from '@/utils/cache'
import setting from '@/config/setting'

/**
 * 账号Store，管理用户登录状态、信息及相关操作
 */
export const useAccountStore = defineStore('account', () => {
  // ========== State ==========
  /** 当前用户信息 */
  const userInfo = ref<AccountVO | null>(getUser())

  /** 访问令牌 */
  const accessToken = ref<string>(getToken())

  /** 刷新令牌 */
  const refreshToken = ref<string>(getRefreshToken())

  /** 登录状态 */
  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)

  // ========== 组织（内部租户）State ==========

  /** 平台组织持久化缓存键 */
  const TENANT_KEY = setting.systemName + '-current-tenant'
  /** 当前租户下的权限点缓存键 */
  const PERMS_KEY = setting.systemName + '-current-perms'

  /**
   * 当前平台组织信息
   * <p>
   * 仅用于兼容历史缓存与「我的账户」展示；不再提供组织切换能力。
   */
  const currentTenant = ref<TenantInfo | null>(cache.local.getJSON(TENANT_KEY, {}) || null)

  /** 当前账号所属部门（组织）名称 */
  const deptName = computed(() => userInfo.value?.deptName || '')

  // ========== 通用 State ==========

  /**
   * 当前组织下的权限点（单轨权限）
   * <p>
   * 由后端 `GET /auth/perms` 提供（口径：角色 → 菜单/按钮 perms），
   * 登录、刷新令牌后都会重新拉取；本地缓存用于刷新页面时先渲染。
   */
  const perms = ref<string[]>(cache.local.getJSON(PERMS_KEY, []))

  /** 持久化权限点 */
  function persistPerms(value: string[]) {
    perms.value = value
    cache.local.setJSON(PERMS_KEY, value)
  }

  /** 是否拥有指定权限点 */
  function hasPerm(perm: string): boolean {
    return !!perm && perms.value.includes(perm)
  }

  /** 是否拥有任意一个指定权限点 */
  function hasAnyPerm(permList: string[]): boolean {
    if (!permList || permList.length === 0) return false
    return permList.some((perm) => perms.value.includes(perm))
  }

  /** 是否为平台管理员（拥有 system:admin 权限点，只授予内置角色 superadmin/system_admin） */
  const isAdmin = computed(() => hasPerm('system:admin'))

  /**
   * 是否仅具有只读权限
   * <p>
   * 单轨口径：只看「动作型权限点」（形如 `module:resource:action`，含冒号），
   * 全部是只读动作（`:list` / `:view`）即视为只读——与内置角色 viewer 的授权范围一致。
   * <p>
   * 注意：菜单树里的目录节点权限（`space` / `dev` / `resource` / `system` …）不含冒号，
   * 必须排除，否则 viewer 会因为持有这些目录权限而被误判为可编辑。
   * 没有拉到任何动作型权限点时按只读处理（宁可少显示按钮，后端仍会拦截）。
   */
  const isReadOnly = computed(() => {
    const actionPerms = perms.value.filter((perm) => perm.includes(':'))
    if (actionPerms.length === 0) return true
    return actionPerms.every((perm) => /:(list|view)$/.test(perm))
  })

  /** 是否具有编辑权限 */
  const canEdit = computed(() => !isReadOnly.value)

  /**
   * 是否必须先修改密码
   * <p>
   * 口径为「服务端 userInfo 上的标记」：管理员新增用户、或管理员重置密码后后端置 true，
   * 用户自行改密成功后置回 false。登录成功后 {@link refreshLogin} 会重新拉取用户详情，
   * 且 userInfo 已持久化，因此刷新页面后依然生效。
   */
  const mustChangePassword = computed(() => userInfo.value?.mustChangePassword === true)

  /** 是否刷新页面 */
  const isRefresh = ref<number>(0)

  // ========== Actions ==========

  function setRefresh() {
    isRefresh.value = new Date().getMilliseconds();
  }

  function getRefresh() {
    return isRefresh.value;
  }

  /**
   * 持久化当前组织信息
   */
  function persistTenant(tenant: TenantInfo | null) {
    currentTenant.value = tenant
    if (tenant) {
      cache.local.setJSON(TENANT_KEY, tenant)
    } else {
      cache.local.remove(TENANT_KEY)
    }
  }

  /**
   * 从 LoginResponse 的 UserDetail 中提取组织上下文
   * <p>
   * 多组织已下线：只保留一条组织信息用于展示，不再驱动「切换」。
   */
  function extractTenantFromUserDetail(userDetail: LoginResponse['userDetail']) {
    if (!userDetail) return
    if (userDetail.tenantId) {
      persistTenant({
        tenantId: userDetail.tenantId,
        tenantCode: userDetail.tenantCode || '',
        tenantName: userDetail.tenantName || userDetail.tenantCode || '',
        role: userDetail.tenantRole || ''
      })
    }
  }

  /**
   * 刷新访问凭证
   * @param data
   */
  function setAccessInfo(data: LoginResponse) {
    // 保存token
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    setToken({
      value: data.accessToken,
      ttl: data.accessTokenTTL
    })
    setRefreshToken({
      value: data.refreshToken,
      ttl: data.refreshTokenTTL
    })
    // 提取组织信息
    extractTenantFromUserDetail(data.userDetail)
  }

  /**
   * 刷新登录
   * @param data
   */
  async function refreshLogin(data: LoginResponse) {
    setAccessInfo(data)

    // 单轨权限：登录/刷新令牌后重新拉取权限点
    await loadPerms()

    // 如果需要完整的用户信息，可以调用详情接口
    if (data.userDetail?.id) {
      await fetchCurrentUserInfo(data.userDetail.id)
    }
  }

  /**
   * 拉取当前组织下的权限点
   * <p>
   * 权限点为「角色 → 菜单/按钮 perms」的并集，是前端一切可见性判断（v-permission、
   * isAdmin/isReadOnly）的唯一依据；拉取失败时退化为空集合（前端按只读渲染，后端仍会拦截）。
   */
  async function loadPerms(): Promise<string[]> {
    try {
      const response = await authApi.perms()
      const list = response.data?.data || []
      persistPerms(list)
      return list
    } catch (error) {
      console.warn('[权限] 获取权限点失败', error)
      persistPerms([])
      return []
    }
  }

  /**
   * 用户登录
   * <p>
   * 「部门即组织」后一次登录直接进入，返回值只关心是否需要强制改密。
   * @param loginData 登录信息
   */
  async function login(loginData: LoginRequest): Promise<{ mustChangePassword: boolean }> {
    const response = await authApi.login(loginData)
    const data = response.data.data

    // 提取并持久化组织信息
    extractTenantFromUserDetail(data.userDetail)

    await refreshLogin(data)
    return { mustChangePassword: data.mustChangePassword === true }
  }

  /**
   * 刷新Token
   */
  async function refreshAccessToken(): Promise<void> {
    try {
      const response = await authApi.refreshToken(refreshToken.value)
      const data = response.data.data
      await refreshLogin(data)
    } catch (error) {
      // 刷新失败则退出登录
      await logout()
      throw error
    }
  }

  /**
   * 用户退出
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch (error) {
      // 即使请求失败也要清除本地数据
      console.error('Logout API failed:', error)
    } finally {
      // 清除所有状态
      clearUserData()
    }
  }

  /**
   * 修改密码
   * <p>
   * 成功后解除「首次登录必须修改密码」状态。
   * @param data 密码修改信息
   */
  async function changePassword(data: ChangePasswordRequest): Promise<boolean> {
    const response = await authApi.changePassword(data)
    if (response.data.data && userInfo.value) {
      setUserInfo({ ...userInfo.value, mustChangePassword: false })
    }
    return response.data.data
  }

  /**
   * 修改个人信息
   * @param data 个人信息
   */
  async function updateProfile(data: UpdateProfileRequest): Promise<boolean> {
    const response = await authApi.updateProfile(data)
    if (response.data.data && userInfo.value) {
      // 更新本地用户信息
      setUserInfo({
        ...userInfo.value,
        ...data,
      })
    }
    return response.data.data
  }

  /**
   * 获取用户详情（通过ID）
   * @param id 用户ID
   */
  async function fetchAccountDetail(id: string): Promise<AccountVO> {
    const response = await accountApi.detail(id)
    return response.data.data
  }

  /**
   * 获取当前用户信息
   * @param userId 用户ID（可选，如果不传则使用当前用户ID）
   */
  async function fetchCurrentUserInfo(userId?: string): Promise<void> {
    const targetId = userId || userInfo.value?.id
    if (!targetId) {
      return
    }
    const data = await fetchAccountDetail(targetId as string)
    setUserInfo(data)
  }

  /**
   * 设置用户信息（用于页面刷新后从localStorage恢复）
   * @param account 用户信息
   */
  function setUserInfo(account: AccountVO): void {
    userInfo.value = account
    setUser(userInfo.value)
  }

  /**
   * 清除用户数据
   */
  function clearUserData(): void {
    useResourceSpaceStore().reset()
    userInfo.value = null
    accessToken.value = ''
    refreshToken.value = ''
    currentTenant.value = null
    perms.value = []
    removeToken()
    removeRefreshToken()
    removeUser()
    cache.local.remove(TENANT_KEY)
    cache.local.remove(PERMS_KEY)
  }

  /**
   * 初始化Store（从localStorage恢复token、用户与组织信息）
   */
  function initStore(): void {
    const token = getToken()
    const refresh = getRefreshToken()
    const user = getUser()
    const tenant = cache.local.getJSON(TENANT_KEY, {}) || null
    const cachedPerms = cache.local.getJSON(PERMS_KEY, [])

    if (token) {
      accessToken.value = token
    }
    if (refresh) {
      refreshToken.value = refresh
    }
    if (user) {
      // 历史缓存里 userInfo 可能被存成「JSON 字符串」（双重编码），直接当对象用会让
      // 下游（如 AG-UI 的 forwardedProps.userInfo）反序列化失败，这里统一规整成对象
      userInfo.value = typeof user === 'string' ? JSON.parse(user) : user
    }
    if (tenant) {
      currentTenant.value = tenant as TenantInfo
    }
    if (Array.isArray(cachedPerms)) {
      perms.value = cachedPerms as string[]
    }
  }

  // 自动初始化
  initStore()

  return {
    // State
    userInfo,
    accessToken,
    refreshToken,
    isLoggedIn,
    isAdmin,
    canEdit,
    isReadOnly,
    currentTenant,
    deptName,
    perms,
    mustChangePassword,

    // Actions
    setRefresh,
    getRefresh,
    login,
    refreshLogin,
    refreshAccessToken,
    logout,
    changePassword,
    updateProfile,
    fetchAccountDetail,
    fetchCurrentUserInfo,
    setUserInfo,
    setAccessInfo,
    clearUserData,
    hasPerm,
    hasAnyPerm,
    loadPerms,
    initStore,
    persistTenant
  }
})
