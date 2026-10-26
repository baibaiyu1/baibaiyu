package com.gmd.faq.server.main.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.account.mapper.AccountMapper;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.entity.RoleAccount;
import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.vo.SystemUserVO;
import com.gmd.faq.server.main.system.mapper.RoleAccountMapper;
import com.gmd.faq.server.main.system.service.DepartmentService;
import com.gmd.faq.server.main.system.service.MemberScopeGuard;
import com.gmd.faq.server.main.system.service.RoleService;
import com.gmd.faq.server.main.system.service.SystemUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统设置 - 用户列表服务实现
 * <p>
 * 「部门即组织」后，用户范围不再靠 {@code gmd_faq_account_tenant} 判定，而是：
 * <ul>
 *   <li>账号的 {@code dept_id} 即其组织归属（可为空 = 未归属组织）；</li>
 *   <li>调用者能看到的范围由「数据权限范围（{@code DataScope}）」决定：
 *       {@code ALL} 全平台、{@code DEPT} 本部门、{@code DEPT_AND_CHILD} 本部门及下级、
 *       {@code SELF} 仅自己；未归属部门的账号只有 {@code ALL} 档能看见。</li>
 * </ul>
 *
 */
@Service
@RequiredArgsConstructor
public class SystemUserServiceImpl implements SystemUserService {

    private final AccountMapper accountMapper;
    private final DepartmentService departmentService;
    private final RoleService roleService;
    private final RoleAccountMapper roleAccountMapper;
    private final MemberScopeGuard memberScopeGuard;

    @Override
    public List<SystemUserVO> listUsers(String keyword, Long deptId, Boolean enabled) {
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (tenantId == null) {
            return new ArrayList<>();
        }

        // 1) 部门（组织）字典：一次查出，后续按名称/负责人/租户归属复用
        List<Department> departments = departmentService.list();
        Map<Long, Department> deptMap = departments.stream()
                .collect(Collectors.toMap(Department::getId, item -> item, (a, b) -> a));
        Map<Long, String> deptNameMap = departments.stream()
                .collect(Collectors.toMap(Department::getId, Department::getName, (a, b) -> a));
        // 组织负责人支持多选：一次查出全部关联后摊平成「担任负责人的账号集合」
        Set<Long> leaderAccountIds = departmentService.listLeaderMap(
                        departments.stream().map(Department::getId).collect(Collectors.toList()))
                .values().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2) 账号集合：账号表跨组织共享（已在租户拦截器白名单里），可见范围在内存中收敛
        List<Account> accounts = accountMapper.selectList(Wrappers.<Account>lambdaQuery());
        if (accounts.isEmpty()) {
            return new ArrayList<>();
        }

        // 3) 调用者的数据权限范围
        DataScope dataScope = currentDataScope();
        boolean onlySelf = dataScope == DataScope.SELF || dataScope == DataScope.CUSTOM;
        Set<Long> visibleDeptIds = visibleDeptIds(dataScope);
        Long currentAccountId = UserUtils.getId();

        // 4) 显式传入的部门筛选（含其全部下级部门），与数据范围取交集
        Set<Long> filterDeptIds = deptId == null
                ? null
                : new LinkedHashSet<>(departmentService.listSelfAndDescendantIds(deptId));

        // 5) 已分配的角色（只统计平台级 + 账号自身部门的角色）
        Map<Long, List<Role>> accountRoleMap = resolveAccountRoles(accounts);

        String lowerKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase();

        return accounts.stream()
                .filter(account -> enabled == null || Objects.equals(account.getEnabled(), enabled))
                .filter(account -> belongsToTenant(account, deptMap, tenantId))
                .filter(account -> matchesVisibleScope(account, visibleDeptIds, onlySelf,
                        currentAccountId, leaderAccountIds))
                .filter(account -> filterDeptIds == null
                        || (account.getDeptId() != null && filterDeptIds.contains(account.getDeptId())))
                .filter(account -> {
                    if (lowerKeyword == null) {
                        return true;
                    }
                    return contains(account.getNickname(), lowerKeyword)
                            || contains(account.getUsername(), lowerKeyword)
                            || contains(account.getEmail(), lowerKeyword);
                })
                .map(account -> toVO(account, deptNameMap, leaderAccountIds,
                        accountRoleMap.getOrDefault(account.getId(), Collections.emptyList())))
                .sorted((a, b) -> {
                    String left = a.getCreatedAt() == null ? "" : a.getCreatedAt().toString();
                    String right = b.getCreatedAt() == null ? "" : b.getCreatedAt().toString();
                    return right.compareTo(left);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDept(Long accountId, Long deptId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException("用户不存在");
        }
        if (deptId != null && departmentService.getById(deptId) == null) {
            throw new BusinessException("部门（组织）不存在");
        }
        // 越权防护：源组织与目标组织都必须落在调用者的可见范围内
        if (!canManageDepartment(account.getDeptId())) {
            throw new BusinessException("无权管理该账号所属组织");
        }
        if (!canManageDepartment(deptId)) {
            throw new BusinessException("无权把账号分配到该组织");
        }
        if (deptId == null) {
            // 「移出组织」：dept_id 允许被清空（null 会被 not_null 更新策略跳过，故直接写 SQL 置空）
            accountMapper.update(null, Wrappers.<Account>lambdaUpdate()
                    .eq(Account::getId, accountId)
                    .set(Account::getDeptId, null));
        } else {
            Account update = new Account();
            update.setId(accountId);
            update.setDeptId(deptId);
            accountMapper.updateById(update);
        }
        // 归属变化后解绑不再适用的部门级角色（平台级角色保留）
        removeInvalidDeptRoles(accountId, deptId);
        // 若该账号原本是某部门负责人，移出后清掉负责人引用，避免悬挂
        clearLeaderReferenceIfNeeded(accountId, deptId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignRoles(Long accountId, List<Long> roleIds) {
        Long tenantId = TenantUtils.getCurrentTenantId();
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException("用户不存在");
        }

        // 可分配范围：平台级角色 + 该账号所属组织及其祖先组织的角色
        Map<Long, Role> assignable = roleService.listAssignableRoles(account.getDeptId()).stream()
                .collect(Collectors.toMap(Role::getId, item -> item, (a, b) -> a));

        List<Long> distinct = roleIds == null
                ? Collections.emptyList()
                : roleIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

        // 先校验再删除：否则一次非法请求就会把用户原有角色清空
        for (Long roleId : distinct) {
            if (!assignable.containsKey(roleId)) {
                Role role = roleService.getById(roleId);
                throw new BusinessException(role == null
                        ? "角色不存在：" + roleId
                        : "角色「" + role.getName() + "」不属于该账号所在组织，无法分配");
            }
        }

        roleAccountMapper.delete(Wrappers.<RoleAccount>lambdaQuery()
                .eq(RoleAccount::getAccountId, accountId));

        if (distinct.isEmpty()) {
            return true;
        }
        for (Long roleId : distinct) {
            Role role = assignable.get(roleId);
            RoleAccount relation = new RoleAccount();
            relation.setId(IdWorker.getId());
            relation.setRoleId(roleId);
            relation.setAccountId(accountId);
            relation.setTenantId(role.getTenantId() != null ? role.getTenantId() : tenantId);
            roleAccountMapper.insert(relation);
        }
        return true;
    }

    @Override
    public boolean canManageDepartment(Long deptId) {
        return memberScopeGuard.canManageDepartment(deptId);
    }

    /**
     * 当前请求的数据权限范围（口径集中在 {@link MemberScopeGuard}）
     */
    private DataScope currentDataScope() {
        return memberScopeGuard.currentDataScope();
    }

    /**
     * 调用者可看到的部门（组织）ID集合
     *
     * @return {@code null} 表示不限部门（ALL）；空集合表示只能看自己
     */
    private Set<Long> visibleDeptIds(DataScope dataScope) {
        return memberScopeGuard.visibleDeptIds();
    }

    /**
     * 账号是否落在调用者的可见范围内
     * <p>
     * 例外：组织负责人（department.leader_account_id）始终对拥有成员管理能力的调用者可见——
     * 负责人可能是上级组织的成员，甚至尚未归属任何组织，严格按部门过滤会让自己的负责人「消失」。
     */
    private boolean matchesVisibleScope(Account account, Set<Long> visibleDeptIds,
                                        boolean onlySelf, Long currentAccountId,
                                        Set<Long> leaderAccountIds) {
        if (onlySelf) {
            return Objects.equals(account.getId(), currentAccountId);
        }
        // ALL 档：不限部门
        if (visibleDeptIds == null) {
            return true;
        }
        if (leaderAccountIds.contains(account.getId())) {
            return true;
        }
        // 部门档：只放开本部门（及下级）的账号；未归属部门的账号不属于任何组织，部门档看不到
        return account.getDeptId() != null && visibleDeptIds.contains(account.getDeptId());
    }

    /**
     * 账号是否属于当前租户
     * <p>
     * 账号表跨租户共享，归属由 dept_id 反查部门决定；未归属部门的账号视为当前租户可见
     * （否则管理员无法把它分配到任何组织）。
     */
    private boolean belongsToTenant(Account account, Map<Long, Department> deptMap, Long tenantId) {
        if (account.getDeptId() == null) {
            return true;
        }
        Department dept = deptMap.get(account.getDeptId());
        return dept == null || dept.getTenantId() == null || Objects.equals(dept.getTenantId(), tenantId);
    }

    /**
     * 查询这批账号已分配的角色（排除不再适用的部门级角色）
     * <p>
     * 判定口径与鉴权一致：角色归属为 null（平台级），或落在「账号所属组织及其祖先组织」链上。
     */
    private Map<Long, List<Role>> resolveAccountRoles(List<Account> accounts) {
        List<Long> accountIds = accounts.stream().map(Account::getId).collect(Collectors.toList());
        Map<Long, List<Role>> raw = roleService.mapRolesByAccountIds(accountIds);
        Map<Long, Account> accountMap = accounts.stream()
                .collect(Collectors.toMap(Account::getId, item -> item, (a, b) -> a));

        // 每个涉及到的组织只算一次祖先链，避免逐个账号重复查部门表
        Map<Long, Set<Long>> lineageCache = new HashMap<>();
        for (Account account : accounts) {
            if (account.getDeptId() != null && !lineageCache.containsKey(account.getDeptId())) {
                lineageCache.put(account.getDeptId(),
                        new LinkedHashSet<>(departmentService.listSelfAndAncestorIds(account.getDeptId())));
            }
        }

        return raw.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    Account account = accountMap.get(entry.getKey());
                    Set<Long> lineage = account == null || account.getDeptId() == null
                            ? Collections.emptySet()
                            : lineageCache.getOrDefault(account.getDeptId(), Collections.emptySet());
                    return entry.getValue().stream()
                            .filter(role -> role.getDeptId() == null || lineage.contains(role.getDeptId()))
                            .collect(Collectors.toList());
                }));
    }

    /**
     * 账号部门变更后，解绑已不再适用的部门级角色
     */
    private void removeInvalidDeptRoles(Long accountId, Long deptId) {
        List<RoleAccount> relations = roleAccountMapper.selectList(
                Wrappers.<RoleAccount>lambdaQuery().eq(RoleAccount::getAccountId, accountId));
        if (relations.isEmpty()) {
            return;
        }
        Map<Long, Role> roleMap = roleService.listByIds(
                        relations.stream().map(RoleAccount::getRoleId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Role::getId, item -> item, (a, b) -> a));
        List<Long> invalidRelationIds = relations.stream()
                .filter(relation -> {
                    Role role = roleMap.get(relation.getRoleId());
                    return role != null && role.getDeptId() != null && !Objects.equals(role.getDeptId(), deptId);
                })
                .map(RoleAccount::getId)
                .collect(Collectors.toList());
        if (!invalidRelationIds.isEmpty()) {
            roleAccountMapper.delete(Wrappers.<RoleAccount>lambdaQuery()
                    .in(RoleAccount::getId, invalidRelationIds));
        }
    }

    /**
     * 账号移出组织时，摘掉它作为「部门（组织）负责人」的身份
     * <p>
     * 负责人是多选（关联表），所以逐个「该账号担任负责人的组织」做一次集合更新：
     * 只保留仍与其新归属一致的那个组织，其余全部摘除，避免负责人指向外部组织的账号。
     *
     * @param accountId  账号ID
     * @param newDeptId  账号的新归属部门ID，null 表示已移出组织
     */
    private void clearLeaderReferenceIfNeeded(Long accountId, Long newDeptId) {
        List<Long> leaderDeptIds = departmentService.listLeaderDepartmentIds(accountId);
        for (Long leaderDeptId : leaderDeptIds) {
            if (newDeptId != null && Objects.equals(leaderDeptId, newDeptId)) {
                continue;
            }
            List<Long> remaining = departmentService.listLeaderAccountIds(leaderDeptId).stream()
                    .filter(id -> !Objects.equals(id, accountId))
                    .collect(Collectors.toList());
            departmentService.updateLeaderAccounts(leaderDeptId, remaining);
        }
    }

    private SystemUserVO toVO(Account account, Map<Long, String> deptNameMap,
                              Set<Long> leaderAccountIds, List<Role> roles) {
        SystemUserVO vo = new SystemUserVO();
        vo.setId(account.getId());
        vo.setNickname(account.getNickname());
        vo.setUsername(account.getUsername());
        vo.setEmail(account.getEmail());
        vo.setEnabled(account.getEnabled());
        vo.setDeptId(account.getDeptId());
        vo.setDeptName(account.getDeptId() == null ? null : deptNameMap.get(account.getDeptId()));
        vo.setLeader(leaderAccountIds.contains(account.getId()));
        vo.setMustChangePassword(Boolean.TRUE.equals(account.getMustChangePassword()));
        vo.setCreatedAt(account.getCreatedAt());
        vo.setUpdatedAt(account.getUpdatedAt());
        vo.setRoleIds(roles.stream().map(Role::getId).collect(Collectors.toList()));
        vo.setRoleNames(roles.stream().map(Role::getName).collect(Collectors.toList()));
        return vo;
    }

    private boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }
}
