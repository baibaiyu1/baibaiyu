package com.gmd.faq.server.main.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.dto.RoleQueryDTO;
import com.gmd.faq.server.main.common.dto.RoleSaveDTO;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.vo.RoleVO;

import java.util.List;

/**
 * 角色 Service
 *
 */
public interface RoleService extends IService<Role> {

    /**
     * 查询角色列表（带菜单权限ID与用户数）
     *
     * @param query 查询条件
     * @return 角色列表
     */
    List<RoleVO> listRoles(RoleQueryDTO query);

    /**
     * 角色详情
     *
     * @param id 角色ID
     * @return 角色，不存在返回 null
     */
    RoleVO detail(Long id);

    /**
     * 新增角色（含菜单权限）
     *
     * @param dto 角色保存请求
     * @return 保存后的角色
     */
    Role create(RoleSaveDTO dto);

    /**
     * 修改角色（含菜单权限）
     *
     * @param dto 角色保存请求
     * @return 是否成功
     */
    boolean update(RoleSaveDTO dto);

    /**
     * 删除角色（内置角色、仍被账号引用的角色拒绝删除）
     *
     * @param id 角色ID
     * @return 是否成功
     */
    boolean remove(Long id);

    /**
     * 修改数据权限范围
     *
     * @param id 角色ID
     * @param dataScope 数据权限范围枚举名
     * @return 是否成功
     */
    boolean updateDataScope(Long id, String dataScope);

    /**
     * 分配用户（全量覆盖该角色下的账号）
     *
     * @param roleId 角色ID
     * @param accountIds 账号ID集合
     * @return 是否成功
     */
    boolean assignUsers(Long roleId, List<Long> accountIds);

    /**
     * 查询角色已分配的账号ID集合
     *
     * @param roleId 角色ID
     * @return 账号ID集合
     */
    List<Long> listAssignedAccountIds(Long roleId);

    /**
     * 查询账号已分配的角色
     *
     * @param accountIds 账号ID集合
     * @return accountId -> 角色列表
     */
    java.util.Map<Long, List<Role>> mapRolesByAccountIds(java.util.Collection<Long> accountIds);

    /**
     * 查询指定归属范围下可用的角色（平台级 + 该组织专属）
     *
     * @param department 部门（组织）ID，可为空
     * @return 可用角色列表
     */
    List<Role> listAssignableRoles(Long department);
}
