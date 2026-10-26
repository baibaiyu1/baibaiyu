package com.gmd.faq.server.main.console.system;

import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.SystemUserVO;
import com.gmd.faq.server.main.system.service.SystemUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 系统设置 - 用户列表 Controller
 * <p>
 * 「部门即组织」后，用户列表是**唯一**的账号管理入口（原「全部账户」页已下线）：
 * 账号的 {@code dept_id} 即其组织归属，可见范围由调用者的数据权限范围（{@code DataScope}）决定。
 *
 */
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SystemUserController {

    private final SystemUserService systemUserService;

    /**
     * 用户列表
     *
     * @param keyword 昵称/用户名/邮箱关键字
     * @param deptId  部门（组织）ID（传入时按该部门及其全部下级部门筛选）
     * @param enabled 是否可用
     */
    @GetMapping("/list")
    public R<List<SystemUserVO>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "deptId", required = false) Long deptId,
            @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return R.data(systemUserService.listUsers(keyword, deptId, enabled));
    }

    /**
     * 设置用户所属部门（组织）
     * <p>
     * 不传 {@code deptId} 表示「移出组织」——账号本身保留，只是不再归属任何部门。
     * 删除账号请使用 {@code DELETE /account}。
     */
    @PermNeed({"system:user:edit"})
    @PutMapping("/{id}/department")
    public R<Boolean> updateDepartment(@PathVariable("id") Long id,
                                       @RequestParam(value = "deptId", required = false) Long deptId) {
        return R.data(systemUserService.updateDept(id, deptId),
                deptId == null ? "已移出组织" : "所属组织已更新");
    }

    /**
     * 分配角色（全量覆盖）
     * <p>
     * 部门级角色只能分配给该部门（组织）内的账号。
     */
    @PermNeed({"system:user:edit"})
    @PutMapping("/{id}/roles")
    public R<Boolean> assignRoles(@PathVariable("id") Long id, @RequestBody List<Long> roleIds) {
        return R.data(systemUserService.assignRoles(id, roleIds), "角色分配已保存");
    }
}
