package com.gmd.faq.server.main.system.service;

import com.gmd.faq.server.main.account.mapper.AccountMapper;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.util.UserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 组织（部门）成员管理范围守卫
 * <p>
 * 「部门即组织」后，账号的组织归属就是 {@code account.dept_id}，
 * 因此「能不能把账号放到某个组织」「能不能调动某个组织的成员」必须与用户列表的可见范围
 * （{@code DataScope}）保持同一口径，否则只有 {@code system:user:add} 的部门管理员
 * 可以把账号建到任意组织（越权）。
 * <p>
 * 单独抽成一个组件而不是放在 {@code SystemUserServiceImpl} 里，是为了让
 * {@code AccountServiceImpl} 也能复用而不产生 Bean 循环依赖。
 *
 */
@Component
@RequiredArgsConstructor
public class MemberScopeGuard {

    private final DepartmentService departmentService;
    private final AccountMapper accountMapper;

    /**
     * 校验调用者是否有权管理指定组织（部门）的成员
     *
     * @param deptId 目标组织（部门）ID，null / 0 表示「不属于任何组织」
     * @return 是否允许
     */
    public boolean canManageDepartment(Long deptId) {
        DataScope dataScope = currentDataScope();
        // 「不属于任何组织」的账号只有全平台管理员能安置
        if (deptId == null || deptId == 0L) {
            return dataScope == DataScope.ALL;
        }
        Set<Long> visibleDeptIds = visibleDeptIds(dataScope);
        return visibleDeptIds == null || visibleDeptIds.contains(deptId);
    }

    /**
     * 校验调用者是否有权管理指定账号
     * <p>
     * 口径 = 「该账号所属组织落在我的可见范围内」，全平台管理员（{@code ALL}）不限。
     * 用于「把某账号设为我管理的组织负责人」这类需要同时校验**目标组织**与**目标账号**的场景。
     *
     * @param accountId 账号ID
     * @return 是否允许
     */
    public boolean canManageAccount(Long accountId) {
        if (accountId == null) {
            return false;
        }
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            return false;
        }
        return canManageDepartment(account.getDeptId());
    }

    /**
     * 当前请求的数据权限范围（与 {@code ResourceVisibilityServiceImpl} 口径一致，非法值按 SELF）
     */
    public DataScope currentDataScope() {
        String scopeName = UserUtils.getUserDetail() == null ? null : UserUtils.getUserDetail().getDataScope();
        return DataScope.isValid(scopeName)
                ? DataScope.valueOf(scopeName.trim().toUpperCase(Locale.ROOT))
                : DataScope.SELF;
    }

    /**
     * 调用者可管理的组织（部门）ID集合
     *
     * @return {@code null} 表示不限组织（ALL）；空集合表示只能管自己
     */
    public Set<Long> visibleDeptIds() {
        return visibleDeptIds(currentDataScope());
    }

    private Set<Long> visibleDeptIds(DataScope dataScope) {
        Long deptId = UserUtils.getUserDetail() == null ? null : UserUtils.getUserDetail().getDeptId();
        return switch (dataScope) {
            case ALL -> null;
            case DEPT, DEPT_AND_CHILD, DEPT_AND_CHILD_OR_SELF -> deptId == null
                    ? Collections.emptySet()
                    : new LinkedHashSet<>(departmentService.listSelfAndDescendantIds(deptId));
            case SELF, CUSTOM -> Collections.emptySet();
        };
    }
}
