package com.gmd.faq.server.main.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gmd.faq.server.main.common.dto.DepartmentDTO;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.vo.DepartmentVO;

import java.util.List;

/**
 * 部门 Service（部门即组织）
 *
 */
public interface DepartmentService extends IService<Department> {

    /**
     * 查询部门树（带直属账号数与组织成员数）
     *
     * @param query 查询条件
     * @return 部门树
     */
    List<DepartmentVO> listTree(DepartmentDTO query);

    /**
     * 查询部门平铺列表（按显示顺序）
     *
     * @param query 查询条件
     * @return 部门列表
     */
    List<Department> listFlat(DepartmentDTO query);

    /**
     * 部门详情
     *
     * @param id 部门ID
     * @return 部门，不存在返回 null
     */
    DepartmentVO detail(Long id);

    /**
     * 新增部门
     *
     * @param entity 部门
     * @return 保存后的部门
     */
    Department create(Department entity);

    /**
     * 修改部门
     *
     * @param entity 部门
     * @return 是否成功
     */
    boolean update(Department entity);

    /**
     * 删除部门（存在子部门或已分配用户时拒绝）
     *
     * @param id 部门ID
     * @return 是否成功
     */
    boolean remove(Long id);

    /**
     * 查询指定部门及其全部子孙部门ID（用于用户列表按部门树筛选）
     *
     * @param deptId 部门ID
     * @return 部门ID集合（含自身），deptId 为空时返回空集合
     */
    List<Long> listSelfAndDescendantIds(Long deptId);

    /**
     * 查询指定部门及其全部祖先部门ID
     * <p>
     * 用于「部门级角色」的可用范围：账号能拿到的角色包含其祖先组织的角色
     * （上级组织管理员可管理下级组织），因此角色查询需要这条祖先链。
     *
     * @param deptId 部门ID
     * @return 部门ID集合（含自身，顺序为自身 → 顶级），deptId 为空时返回空集合
     */
    List<Long> listSelfAndAncestorIds(Long deptId);

    /**
     * 查询一批部门的负责人账号ID（按部门归并）
     * <p>
     * 一次查出全部关联后在内存分组，供组织树/用户列表批量渲染，避免 N+1。
     *
     * @param deptIds 部门ID集合，可为空
     * @return departmentId -> 负责人账号ID列表（按设置顺序）
     */
    java.util.Map<Long, List<Long>> listLeaderMap(java.util.Collection<Long> deptIds);

    /**
     * 查询单个部门的负责人账号ID列表
     *
     * @param deptId 部门ID
     * @return 负责人账号ID列表，无负责人时返回空集合
     */
    List<Long> listLeaderAccountIds(Long deptId);

    /**
     * 查询某账号担任负责人的组织（部门）ID列表
     *
     * @param accountId 账号ID
     * @return 部门ID列表
     */
    List<Long> listLeaderDepartmentIds(Long accountId);

    /**
     * 全量覆盖组织负责人（多选）
     * <p>
     * 传空集合即清空负责人。会校验每个账号真实存在。
     *
     * @param deptId     部门ID
     * @param accountIds 负责人账号ID列表，可为空
     */
    void updateLeaderAccounts(Long deptId, List<Long> accountIds);

    /**
     * 移除某账号的全部负责人身份
     * <p>
     * 账号被删除、或移出组织时调用，避免负责人关联指向不存在/不该继续担任的账号。
     *
     * @param accountId 账号ID
     */
    void removeLeaderAccount(Long accountId);
}
