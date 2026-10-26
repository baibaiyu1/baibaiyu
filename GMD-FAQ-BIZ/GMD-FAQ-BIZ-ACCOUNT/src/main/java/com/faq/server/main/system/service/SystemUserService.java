package com.gmd.faq.server.main.system.service;

import com.gmd.faq.server.main.common.vo.SystemUserVO;

import java.util.List;

/**
 * 系统设置 - 用户列表 Service
 * <p>
 * 「部门即组织」后，用户列表以「账号 + 所属部门（组织）+ 已分配角色」为口径，
 * 可见范围由调用者的数据权限范围（{@code DataScope}）决定，
 * 不再依赖已废弃的 {@code gmd_faq_account_tenant} 成员关系。
 *
 */
public interface SystemUserService {

    /**
     * 用户列表
     *
     * @param keyword 昵称/用户名/邮箱关键字，可空
     * @param deptId  部门（组织）ID，可空；传入时按「该部门及其全部下级部门」筛选
     * @param enabled 是否可用，可空
     * @return 用户列表
     */
    List<SystemUserVO> listUsers(String keyword, Long deptId, Boolean enabled);

    /**
     * 设置用户所属部门（组织）
     *
     * @param accountId 账号ID
     * @param deptId    部门ID，传 null 表示移出组织
     * @return 是否成功
     */
    boolean updateDept(Long accountId, Long deptId);

    /**
     * 分配角色（全量覆盖）
     * <p>
     * 部门级角色只能分配给该部门（组织）内的账号，平台级角色不限。
     *
     * @param accountId 账号ID
     * @param roleIds   角色ID集合
     * @return 是否成功
     */
    boolean assignRoles(Long accountId, List<Long> roleIds);

    /**
     * 校验调用者是否有权管理指定组织（部门）的成员
     * <p>
     * 口径与用户列表的可见范围一致（{@code DataScope}）：{@code ALL} 不限；
     * 部门档只允许自己所属组织（{@code DEPT}）或所属组织及其下级（{@code DEPT_AND_CHILD}）；
     * {@code SELF} 档不允许管理任何他人。
     * <p>
     * 供「新增用户到某组织」「修改用户所属组织」「设置组织负责人」等入口复用，
     * 否则仅有 {@code system:user:add} 的组织管理员可以把账号建到任意组织（越权）。
     *
     * @param deptId 目标组织（部门）ID，null 表示「不属于任何组织」
     * @return 是否允许
     */
    boolean canManageDepartment(Long deptId);
}
