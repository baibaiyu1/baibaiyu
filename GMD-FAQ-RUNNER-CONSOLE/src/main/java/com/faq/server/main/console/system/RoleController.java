package com.gmd.faq.server.main.console.system;

import com.gmd.faq.server.main.account.service.AccountService;
import com.gmd.faq.server.main.common.dto.RoleQueryDTO;
import com.gmd.faq.server.main.common.dto.RoleSaveDTO;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.entity.Role;
import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.vo.RoleVO;
import com.gmd.faq.server.main.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 角色管理 Controller
 * <p>
 * 对应「系统设置 - 角色配置」：列表、编辑、分配数据权限、分配用户。
 * <p>
 * 「部门即组织」后角色分两档：{@code dept_id} 为空是平台级角色（全平台可用），
 * 非空是该组织（部门）的专属角色（如「XX部主管」），只能分配给该组织内的账号。
 *
 */
@RestController
@RequestMapping("/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final AccountService accountService;

    /**
     * 角色列表（带菜单权限ID与已分配用户数）
     *
     * @param deptId 组织（部门）ID，传入时返回「平台级角色 + 该组织专属角色」
     */
    @GetMapping("/list")
    public R<List<RoleVO>> list(RoleQueryDTO query) {
        return R.data(roleService.listRoles(query));
    }

    /**
     * 数据权限范围可选值（供前端下拉直接渲染）
     */
    @GetMapping("/data-scopes")
    public R<List<Map<String, String>>> dataScopes() {
        List<Map<String, String>> options = new ArrayList<>();
        for (DataScope item : DataScope.values()) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("value", item.name());
            option.put("label", item.getDescription());
            options.add(option);
        }
        return R.data(options);
    }

    /**
     * 角色详情
     */
    @GetMapping("/{id}")
    public R<RoleVO> detail(@PathVariable("id") Long id) {
        RoleVO vo = roleService.detail(id);
        if (vo == null) {
            return R.fail("角色不存在");
        }
        return R.data(vo);
    }

    /**
     * 新增角色
     */
    @PermNeed({"system:role:add"})
    @PostMapping
    public R<Role> save(@RequestBody RoleSaveDTO dto) {
        return R.data(roleService.create(dto), "角色创建成功");
    }

    /**
     * 修改角色（含菜单权限）
     */
    @PermNeed({"system:role:edit"})
    @PutMapping("/{id}")
    public R<Boolean> update(@PathVariable("id") Long id, @RequestBody RoleSaveDTO dto) {
        dto.setId(id);
        return R.data(roleService.update(dto), "角色已更新");
    }

    /**
     * 分配数据权限
     */
    @PermNeed({"system:role:dataScope"})
    @PutMapping("/{id}/data-scope")
    public R<Boolean> updateDataScope(@PathVariable("id") Long id,
                                      @RequestParam("dataScope") String dataScope) {
        return R.data(roleService.updateDataScope(id, dataScope), "数据权限范围已更新");
    }

    /**
     * 查询角色已分配的用户ID列表
     */
    @GetMapping("/{id}/users")
    public R<List<Long>> assignedUsers(@PathVariable("id") Long id) {
        return R.data(roleService.listAssignedAccountIds(id));
    }

    /**
     * 查询某账号可分配的角色（平台级 + 其所属组织的角色）
     * <p>
     * 「分配用户」弹窗用它渲染候选清单：部门级角色不能跨组织分配。
     */
    @GetMapping("/assignable")
    public R<List<RoleVO>> assignable(@RequestParam("accountId") Long accountId) {
        Account account = accountService.getById(accountId);
        if (account == null) {
            return R.fail("用户不存在");
        }
        List<Role> roles = roleService.listAssignableRoles(account.getDeptId());
        return R.data(roles.stream()
                .map(role -> BeanUtils.copy(role, RoleVO.class))
                .collect(Collectors.toList()));
    }

    /**
     * 分配用户（全量覆盖）
     */
    @PermNeed({"system:role:assignUser"})
    @PutMapping("/{id}/users")
    public R<Boolean> assignUsers(@PathVariable("id") Long id, @RequestBody List<Long> accountIds) {
        return R.data(roleService.assignUsers(id, accountIds), "用户分配已保存");
    }

    /**
     * 删除角色
     */
    @PermNeed({"system:role:remove"})
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable("id") Long id) {
        return R.data(roleService.remove(id), "角色已删除");
    }
}