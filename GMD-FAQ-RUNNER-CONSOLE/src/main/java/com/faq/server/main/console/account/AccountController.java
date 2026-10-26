package com.gmd.faq.server.main.console.account;

import com.gmd.faq.server.main.account.service.AccountService;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.dto.AccountCreateRequest;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.vo.AccountCredentialVO;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.system.mapper.DepartmentMapper;
import com.gmd.faq.server.main.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 账号Controller
 *
 */
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final DepartmentMapper departmentMapper;
    private final RoleService roleService;

    /**
     * 详情
     * <p>
     * 「部门即组织」后账号详情会带出所属部门（组织）与已分配角色，
     * 前端「我的账户」与权限判定都依赖这两个字段。
     */
    @SkAccess
    @GetMapping("/{id}")
    public R<AccountVO> detail(@PathVariable("id") Long id) {
        if (UserUtils.isSk() && !id.equals(UserUtils.getResourceAccountId())) {
            throw new BusinessException("无权查看该账号");
        }
        Account entity = accountService.getById(id);
        if (entity == null) {
            throw new BusinessException("用户不存在");
        }
        return R.data(toVO(entity));
    }

    /**
     * 组装账号 VO：补齐所属部门（组织）名称与已分配角色
     * <p>
     * 已在 {@code AccountServiceImpl#createAccount} 中改为按请求里的部门决定组织归属，
     * 留空即「无组织账号」（允许存在，数据范围退化为仅本人）。
     */
    private AccountVO toVO(Account entity) {
        AccountVO account = BeanUtils.copy(entity, AccountVO.class);
        if (entity.getDeptId() != null) {
            Department department = departmentMapper.selectById(entity.getDeptId());
            if (department != null) {
                account.setDeptName(department.getName());
            }
        }
        List<Role> roles = roleService.mapRolesByAccountIds(List.of(entity.getId()))
                .getOrDefault(entity.getId(), List.of());
        account.setRoleIds(roles.stream().map(Role::getId).collect(Collectors.toList()));
        account.setRoleNames(roles.stream().map(Role::getName).collect(Collectors.toList()));
        return account;
    }

    /**
     * 管理员新增用户
     * <p>
     * 系统已关闭自助注册：账号只能由拥有 `system:user:add` 权限的管理员创建，
     * 组织归属由请求体里的 {@code deptId} 决定（留空即无组织）。密码留空时由服务端随机生成，
     * 明文仅在本次响应中返回一次，且用户首次登录后会被强制要求修改密码。
     */
    @PostMapping
    @PermNeed({"system:user:add"})
    public R<AccountCredentialVO> save(@RequestBody AccountCreateRequest request) {
        return R.data(accountService.createAccount(request));
    }

    /**
     * 删除用户
     * <p>
     * 同时清理该用户在所有角色下的绑定，以及「部门负责人」引用。
     * 仅把用户移出组织请使用 {@code PUT /system/user/{id}/department}。
     */
    @DeleteMapping
    @PermNeed({"system:user:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(accountService.deleteAccounts(ids));
    }

    /**
     * 禁用/激活用户
     */
    @PermNeed({"system:user:edit"})
    @PutMapping("/{id}/toggle-enabled")
    public R<Boolean> toggleEnabled(@PathVariable("id") Long id, @RequestParam("enabled") Boolean enabled) {
        return R.data(accountService.toggleEnabled(id, enabled));
    }

    /**
     * 管理员重置用户密码
     * <p>
     * 新密码留空时由服务端随机生成，明文仅在本次响应中返回一次，用户下次登录需再次修改。
     */
    @PermNeed({"system:user:resetPwd"})
    @PutMapping("/{id}/reset-password")
    public R<AccountCredentialVO> resetPassword(@PathVariable("id") Long id,
                                                @RequestParam(value = "newPassword", required = false) String newPassword) {
        return R.data(accountService.resetPassword(id, newPassword));
    }
}
