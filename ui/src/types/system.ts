/**
 * 系统设置（部门=组织 / 角色 / 菜单 / 用户列表）类型定义
 *
 * 注意：本文件刻意不从 `@/types` 桶文件导出。
 * `./enums` 已导出一个历史遗留的 `Role` 枚举，若再次导出同名类型会触发
 * TS2308（Module './enums' has already exported a member named 'Role'）。
 * 因此这里统一使用 `Sys*` 前缀，并由需要方直接
 * `import type { ... } from '@/types/system'` 引入。
 *
 */

/**
 * 部门（平铺实体）
 * <p>
 * 「部门即组织」：每个部门节点同时是一个组织，组织资料（code / description /
 * contactName / contactEmail）与负责人（leaderAccountIds，**可多选**）都在这里。
 */
export interface Department {
  id?: string | number
  parentId?: string | number
  name?: string
  /** 组织编码 */
  code?: string
  leader?: string
  /** 负责人账号ID列表（多选；仅提交用，不落库） */
  leaderAccountIds?: Array<string | number>
  phone?: string
  email?: string
  sortOrder?: number
  /** 组织描述 */
  description?: string
  /** 联系人姓名 */
  contactName?: string
  /** 联系人邮箱 */
  contactEmail?: string
  remark?: string
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

/** 部门树节点 */
export interface DepartmentVO extends Department {
  /** 该部门直属账号数 */
  userCount?: number
  /** 该部门及其全部下级部门的账号数（组织成员总数） */
  memberCount?: number
  /** 负责人账号昵称列表，顺序与 leaderAccountIds 一致 */
  leaderNames?: string[]
  children?: DepartmentVO[]
}

/** 角色 */
export interface SysRole {
  id?: string | number
  name?: string
  code?: string
  /** 所属部门（组织）ID；空 = 平台级角色 */
  deptId?: string | number
  /** 所属部门（组织）名称；平台级角色为空 */
  deptName?: string
  sortOrder?: number
  dataScope?: string
  builtin?: boolean
  remark?: string
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

/** 角色VO */
export interface SysRoleVO extends SysRole {
  /** 数据权限范围中文描述 */
  dataScopeLabel?: string
  /** 已勾选的菜单/按钮权限ID集合 */
  menuIds?: Array<string | number>
  /** 已分配用户数 */
  userCount?: number
}

/** 角色保存请求 */
export interface SysRoleSaveDTO {
  name?: string
  code?: string
  deptId?: string | number | null
  sortOrder?: number
  enabled?: boolean
  remark?: string
  dataScope?: string
  menuIds?: Array<string | number>
}

/** 数据权限范围选项 */
export interface DataScopeOption {
  value: string
  label: string
}

/** 菜单类型 */
export type SysMenuType = 'DIRECTORY' | 'MENU' | 'BUTTON'

/** 菜单 */
export interface SysMenu {
  id?: string | number
  parentId?: string | number
  name?: string
  menuType?: SysMenuType
  path?: string
  component?: string
  perms?: string
  icon?: string
  sortOrder?: number
  visible?: boolean
  cached?: boolean
  external?: boolean
  status?: boolean
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 菜单树节点 */
export interface SysMenuVO extends SysMenu {
  children?: SysMenuVO[]
  /** 按钮权限（仅菜单详情返回） */
  buttons?: SysMenuVO[]
}

/** 菜单保存请求 */
export interface SysMenuSaveDTO {
  parentId?: string | number
  name?: string
  menuType?: SysMenuType
  path?: string
  component?: string
  perms?: string
  icon?: string
  sortOrder?: number
  visible?: boolean
  cached?: boolean
  external?: boolean
  status?: boolean
  remark?: string
}

/** 菜单类型选项 */
export interface MenuTypeOption {
  value: string
  label: string
}

/** 系统设置 - 用户列表项 */
export interface SystemUserVO {
  id: string | number
  nickname?: string
  username?: string
  email?: string
  enabled?: boolean
  /** 所属部门（组织）ID；为空表示未归属任何组织 */
  deptId?: string | number
  /** 所属部门（组织）名称 */
  deptName?: string
  /** 是否为所属部门（组织）的负责人 */
  leader?: boolean
  roleIds?: Array<string | number>
  roleNames?: string[]
  /** 是否仍处于「首次登录待改密」状态（管理员新增/重置密码后为 true） */
  mustChangePassword?: boolean
  createdAt?: string
  updatedAt?: string
}

/** 系统设置 - 用户列表查询参数 */
export interface SystemUserQuery {
  keyword?: string
  deptId?: string | number
  enabled?: boolean
}
