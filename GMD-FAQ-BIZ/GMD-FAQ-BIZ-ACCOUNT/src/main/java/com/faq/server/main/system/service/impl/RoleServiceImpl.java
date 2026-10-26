package com.gmd.faq.server.main.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.account.mapper.AccountMapper;
import com.gmd.faq.server.main.common.dto.RoleQueryDTO;
import com.gmd.faq.server.main.common.dto.RoleSaveDTO;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.entity.RoleAccount;
import com.gmd.faq.server.main.common.entity.RoleMenu;
import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.RoleVO;
import com.gmd.faq.server.main.system.mapper.DepartmentMapper;
import com.gmd.faq.server.main.system.mapper.RoleAccountMapper;
import com.gmd.faq.server.main.system.mapper.RoleMapper;
import com.gmd.faq.server.main.system.mapper.RoleMenuMapper;
import com.gmd.faq.server.main.system.service.DepartmentService;
import com.gmd.faq.server.main.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色服务实现
 *
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {

    private final RoleMenuMapper roleMenuMapper;
    private final RoleAccountMapper roleAccountMapper;
    private final DepartmentMapper departmentMapper;
    private final AccountMapper accountMapper;
    private final DepartmentService departmentService;

    @Override
    public List<RoleVO> listRoles(RoleQueryDTO query) {
        String keyword = query == null ? null : query.getKeyword();
        Boolean enabled = query == null ? null : query.getEnabled();
        Long deptId = query == null ? null : query.getDeptId();

        List<Role> roles = lambdaQuery()
                .and(keyword != null && !keyword.isBlank(), wrapper -> wrapper
                        .like(Role::getName, keyword)
                        .or()
                        .like(Role::getCode, keyword))
                .eq(enabled != null, Role::getEnabled, enabled)
                // 部门过滤：平台级角色（dept_id 为空）始终可见，另加该部门自己的角色
                .and(deptId != null, wrapper -> wrapper
                        .isNull(Role::getDeptId)
                        .or()
                        .eq(Role::getDeptId, deptId))
                .orderByAsc(Role::getSortOrder)
                .orderByAsc(Role::getId)
                .list();
        if (roles.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> roleIds = roles.stream().map(Role::getId).collect(Collectors.toList());

        // 菜单权限：一次查出后在内存归并
        Map<Long, List<Long>> menuIdMap = roleMenuMapper.selectList(
                        Wrappers.<RoleMenu>lambdaQuery().in(RoleMenu::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(RoleMenu::getRoleId,
                        Collectors.mapping(RoleMenu::getMenuId, Collectors.toList())));

        // 已分配用户数
        Map<Long, Long> userCountMap = roleAccountMapper.selectList(
                        Wrappers.<RoleAccount>lambdaQuery().in(RoleAccount::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(RoleAccount::getRoleId, Collectors.counting()));

        // 部门（组织）名称
        Map<Long, String> deptNameMap = departmentNameMap(
                roles.stream().map(Role::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return roles.stream().map(role -> {
            RoleVO vo = BeanUtils.copy(role, RoleVO.class);
            vo.setDataScopeLabel(DataScope.describe(role.getDataScope()));
            vo.setDeptName(role.getDeptId() == null ? null : deptNameMap.get(role.getDeptId()));
            vo.setMenuIds(menuIdMap.getOrDefault(role.getId(), new ArrayList<>()));
            vo.setUserCount(userCountMap.getOrDefault(role.getId(), 0L));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public RoleVO detail(Long id) {
        Role role = getById(id);
        if (role == null) {
            return null;
        }
        RoleVO vo = BeanUtils.copy(role, RoleVO.class);
        vo.setDataScopeLabel(DataScope.describe(role.getDataScope()));
        if (role.getDeptId() != null) {
            vo.setDeptName(departmentNameMap(Set.of(role.getDeptId())).get(role.getDeptId()));
        }
        vo.setMenuIds(roleMenuMapper.selectList(
                        Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, id))
                .stream().map(RoleMenu::getMenuId).collect(Collectors.toList()));
        Long count = roleAccountMapper.selectCount(
                Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getRoleId, id));
        vo.setUserCount(count == null ? 0L : count);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Role create(RoleSaveDTO dto) {
        Role role = new Role();
        role.setName(trimToNull(dto.getName()));
        role.setCode(trimToNull(dto.getCode()));
        if (role.getName() == null) {
            throw new BusinessException("角色名称不能为空");
        }
        if (role.getCode() == null) {
            throw new BusinessException("角色权限字符不能为空");
        }
        role.setDeptId(normalizeDeptId(dto.getDeptId()));
        checkCodeUnique(role.getCode(), role.getDeptId(), null);
        role.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        role.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
        role.setRemark(dto.getRemark());
        // 默认数据范围：新建角色按「本部门及以下」起步，需要全平台可见时由管理员显式改
        role.setDataScope(DataScope.isValid(dto.getDataScope())
                ? DataScope.of(dto.getDataScope()).name()
                : DataScope.DEPT_AND_CHILD.name());
        role.setBuiltin(false);
        save(role);
        replaceRoleMenus(role.getId(), dto.getMenuIds());
        return role;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(RoleSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("角色ID不能为空");
        }
        Role exist = getById(dto.getId());
        if (exist == null) {
            throw new BusinessException("角色不存在");
        }
        Role update = new Role();
        update.setId(dto.getId());
        update.setName(trimToNull(dto.getName()));
        update.setCode(trimToNull(dto.getCode()));
        // 内置角色的归属部门不允许改动（它们是平台级角色）
        Long targetDeptId = Boolean.TRUE.equals(exist.getBuiltin())
                ? exist.getDeptId()
                : normalizeDeptId(dto.getDeptId());
        if (update.getCode() != null) {
            checkCodeUnique(update.getCode(), targetDeptId, dto.getId());
        }
        if (!Objects.equals(exist.getDeptId(), targetDeptId)) {
            ensureNoAssignmentAfterDeptChange(dto.getId(), targetDeptId);
            update.setDeptId(targetDeptId);
        }
        update.setSortOrder(dto.getSortOrder());
        update.setEnabled(dto.getEnabled());
        // 备注需要支持「清空」：MP 的 not_null 更新策略会跳过 null 字段，
        // 因此显式传空串落到库里，避免用户改了备注删不掉。
        update.setRemark(dto.getRemark() == null ? "" : dto.getRemark().trim());
        if (DataScope.isValid(dto.getDataScope())) {
            update.setDataScope(DataScope.of(dto.getDataScope()).name());
        }
        boolean ok = updateById(update);
        if (dto.getMenuIds() != null) {
            replaceRoleMenus(dto.getId(), dto.getMenuIds());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id) {
        Role exist = getById(id);
        if (exist == null) {
            throw new BusinessException("角色不存在");
        }
        if (Boolean.TRUE.equals(exist.getBuiltin())) {
            throw new BusinessException("内置角色不允许删除");
        }
        Long assigned = roleAccountMapper.selectCount(
                Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getRoleId, id));
        if (assigned != null && assigned > 0) {
            throw new BusinessException("该角色已分配给用户，请先解除分配");
        }
        roleMenuMapper.delete(Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, id));
        return removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDataScope(Long id, String dataScope) {
        Role exist = getById(id);
        if (exist == null) {
            throw new BusinessException("角色不存在");
        }
        if (!DataScope.isValid(dataScope)) {
            throw new BusinessException("数据权限范围取值非法：" + dataScope);
        }
        Role update = new Role();
        update.setId(id);
        update.setDataScope(DataScope.of(dataScope).name());
        return updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignUsers(Long roleId, List<Long> accountIds) {
        Role exist = getById(roleId);
        if (exist == null) {
            throw new BusinessException("角色不存在");
        }
        Long tenantId = TenantUtils.getCurrentTenantId();

        // 先校验再删除：部门级角色只能分配给该部门（组织）内的账号，
        // 否则一旦中途抛错，旧分配已经被清空，用户会莫名其妙失去全部权限。
        List<Long> distinct = accountIds == null
                ? Collections.emptyList()
                : accountIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (!distinct.isEmpty() && exist.getDeptId() != null) {
            List<Account> accounts = accountMapper.selectBatchIds(distinct);
            for (Account account : accounts) {
                if (!Objects.equals(account.getDeptId(), exist.getDeptId())) {
                    throw new BusinessException("角色「" + exist.getName() + "」属于其他组织，无法分配给："
                            + (account.getNickname() == null ? account.getUsername() : account.getNickname()));
                }
            }
        }

        roleAccountMapper.delete(Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getRoleId, roleId));
        if (distinct.isEmpty()) {
            return true;
        }
        for (Long accountId : distinct) {
            RoleAccount relation = new RoleAccount();
            relation.setId(IdWorker.getId());
            relation.setRoleId(roleId);
            relation.setAccountId(accountId);
            relation.setTenantId(exist.getTenantId() != null ? exist.getTenantId() : tenantId);
            roleAccountMapper.insert(relation);
        }
        return true;
    }

    @Override
    public List<Long> listAssignedAccountIds(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        return roleAccountMapper.selectList(
                        Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getRoleId, roleId))
                .stream().map(RoleAccount::getAccountId).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<Role>> mapRolesByAccountIds(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<RoleAccount> relations = roleAccountMapper.selectList(
                Wrappers.<RoleAccount>lambdaQuery().in(RoleAccount::getAccountId, accountIds));
        if (relations.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> roleIds = relations.stream().map(RoleAccount::getRoleId).collect(Collectors.toSet());
        Map<Long, Role> roleMap = listByIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, item -> item, (a, b) -> a));

        Map<Long, List<Role>> result = new HashMap<>();
        for (RoleAccount relation : relations) {
            Role role = roleMap.get(relation.getRoleId());
            if (role != null) {
                result.computeIfAbsent(relation.getAccountId(), key -> new ArrayList<>()).add(role);
            }
        }
        return result;
    }

    /**
     * 全量覆盖角色的菜单权限
     */
    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId));
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        Long tenantId = TenantUtils.getCurrentTenantId();
        Set<Long> distinct = new LinkedHashSet<>(menuIds);
        for (Long menuId : distinct) {
            if (menuId == null) {
                continue;
            }
            RoleMenu relation = new RoleMenu();
            relation.setId(IdWorker.getId());
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            relation.setTenantId(tenantId);
            roleMenuMapper.insert(relation);
        }
    }

    /**
     * 角色权限字符在同一归属范围内唯一
     * <p>
     * 平台级角色（dept_id 为空）之间唯一；部门级角色在各自部门内唯一，
     * 这样不同组织可以各有一个同名的「主管」角色。
     */
    private void checkCodeUnique(String code, Long deptId, Long excludeId) {
        Long count = lambdaQuery()
                .eq(Role::getCode, code)
                .isNull(deptId == null, Role::getDeptId)
                .eq(deptId != null, Role::getDeptId, deptId)
                .ne(excludeId != null, Role::getId, excludeId)
                .count();
        if (count != null && count > 0) {
            throw new BusinessException(deptId == null
                    ? "平台级角色权限字符已存在：" + code
                    : "该组织下已存在同权限字符的角色：" + code);
        }
    }

    /**
     * 查询指定归属范围下可用的角色
     * <p>
     * 口径与 {@code PermsMapper} 一致：平台级角色 + 该部门（组织）及其**全部祖先部门**的角色。
     * 取祖先是为了让上级组织的管理员能管理下级组织的角色（创建/分配），
     * 但不会把下级组织的角色暴露给上级组织的普通成员——他们依然只能拿到自己的角色。
     *
     * @param department 部门（组织）ID，可为空（为空时只有平台级角色）
     * @return 可用角色列表
     */
    @Override
    public List<Role> listAssignableRoles(Long department) {
        List<Long> deptIds = department == null
                ? Collections.emptyList()
                : departmentService.listSelfAndAncestorIds(department);
        return lambdaQuery()
                .eq(Role::getEnabled, true)
                .and(wrapper -> wrapper
                        .isNull(Role::getDeptId)
                        .or(!deptIds.isEmpty(), inner -> inner.in(Role::getDeptId, deptIds)))
                .orderByAsc(Role::getSortOrder)
                .orderByAsc(Role::getId)
                .list();
    }

    /**
     * 部门（组织）ID 归一化：null / 0 均表示平台级角色
     */
    private Long normalizeDeptId(Long deptId) {
        if (deptId == null || deptId == 0L) {
            return null;
        }
        if (departmentMapper.selectById(deptId) == null) {
            throw new BusinessException("部门（组织）不存在：" + deptId);
        }
        return deptId;
    }

    /**
     * 角色归属部门变更前，确保没有账号还在使用它
     * <p>
     * 否则会出现「账号在 A 组织、角色却属于 B 组织」的悬挂授权，
     * 该账号在鉴权时（{@code role.dept_id} 过滤）会直接丢掉这个角色的全部权限。
     */
    private void ensureNoAssignmentAfterDeptChange(Long roleId, Long targetDeptId) {
        List<RoleAccount> relations = roleAccountMapper.selectList(
                Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getRoleId, roleId));
        if (relations.isEmpty()) {
            return;
        }
        if (targetDeptId == null) {
            // 收敛为平台级角色：所有账号都能继续使用，无需解绑
            return;
        }
        List<Account> accounts = accountMapper.selectBatchIds(
                relations.stream().map(RoleAccount::getAccountId).collect(Collectors.toList()));
        boolean hasForeign = accounts.stream().anyMatch(item -> !Objects.equals(item.getDeptId(), targetDeptId));
        if (hasForeign) {
            throw new BusinessException("该角色已分配给其他组织的账号，请先解除分配再修改归属组织");
        }
    }

    /**
     * 部门（组织）名称映射
     */
    private Map<Long, String> departmentNameMap(Set<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return departmentMapper.selectBatchIds(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || Objects.equals(trimmed, "") ? null : trimmed;
    }
}
