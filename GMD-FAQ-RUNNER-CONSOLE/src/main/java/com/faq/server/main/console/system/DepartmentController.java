package com.gmd.faq.server.main.console.system;

import com.gmd.faq.server.main.common.dto.DepartmentDTO;
import com.gmd.faq.server.main.common.entity.Department;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.DepartmentVO;
import com.gmd.faq.server.main.system.service.DepartmentService;
import com.gmd.faq.server.main.system.service.MemberScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 部门（组织）管理 Controller
 * <p>
 * 「部门即组织」：本接口同时承担原「组织管理」的职责——每个部门节点就是一个组织，
 * 组织资料（编码/描述/联系人）、负责人（**可多选**）与成员都在这里维护。
 * 租户隔离由 MyBatis-Plus 租户拦截器自动完成。
 *
 */
@RestController
@RequestMapping("/department")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;
    private final MemberScopeGuard memberScopeGuard;

    /**
     * 部门（组织）树（系统设置 - 组织与用户左侧树 / 用户列表左侧树共用）
     */
    @GetMapping("/tree")
    public R<List<DepartmentVO>> tree(DepartmentDTO query) {
        return R.data(departmentService.listTree(query));
    }

    /**
     * 部门（组织）平铺列表（下拉选择用）
     */
    @GetMapping("/list")
    public R<List<Department>> list(DepartmentDTO query) {
        return R.data(departmentService.listFlat(query));
    }

    /**
     * 部门（组织）详情
     */
    @GetMapping("/{id}")
    public R<DepartmentVO> detail(@PathVariable("id") Long id) {
        DepartmentVO vo = departmentService.detail(id);
        if (vo == null) {
            return R.fail("部门（组织）不存在");
        }
        return R.data(vo);
    }

    /**
     * 指定部门及其全部下级部门ID（用户列表按部门树筛选）
     */
    @GetMapping("/{id}/descendants")
    public R<List<Long>> descendants(@PathVariable("id") Long id) {
        return R.data(departmentService.listSelfAndDescendantIds(id));
    }

    /**
     * 新增部门（组织）
     * <p>
     * 可同时带上 {@code leaderAccountIds} 指定负责人（多选）。
     */
    @PermNeed({"system:dept:add"})
    @PostMapping
    public R<Department> save(@RequestBody Department entity) {
        Department created = departmentService.create(entity);
        if (entity.getLeaderAccountIds() != null) {
            departmentService.updateLeaderAccounts(created.getId(), entity.getLeaderAccountIds());
        }
        return R.data(created, "部门（组织）创建成功");
    }

    /**
     * 修改部门（组织）
     * <p>
     * {@code leaderAccountIds} 为**全量覆盖**（传空数组即清空负责人），可多选。
     * <p>
     * 负责人是「谁能代表这个组织」的敏感配置，因此要求调用者的可见范围覆盖该组织，
     * 且被指派的每个账号也必须落在自己的可见范围内——否则有 {@code system:dept:edit} 的人
     * 可以把别人的账号（甚至管理员）挂成本组织负责人。
     */
    @PermNeed({"system:dept:edit"})
    @PutMapping("/{id}")
    public R<Boolean> update(@PathVariable("id") Long id, @RequestBody Department entity) {
        entity.setId(id);
        if (entity.getLeaderAccountIds() != null && !entity.getLeaderAccountIds().isEmpty()) {
            if (!memberScopeGuard.canManageDepartment(id)) {
                throw new BusinessException("无权设置该组织的负责人");
            }
            List<Long> outsiders = entity.getLeaderAccountIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .filter(accountId -> !memberScopeGuard.canManageAccount(accountId))
                    .collect(Collectors.toList());
            if (!outsiders.isEmpty()) {
                throw new BusinessException("无权把该账号设为本组织负责人：" + outsiders);
            }
        }
        boolean ok = departmentService.update(entity);
        if (ok && entity.getLeaderAccountIds() != null) {
            departmentService.updateLeaderAccounts(id, entity.getLeaderAccountIds());
        }
        return R.data(ok, "部门（组织）已更新");
    }

    /**
     * 删除部门（组织）
     */
    @PermNeed({"system:dept:remove"})
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable("id") Long id) {
        return R.data(departmentService.remove(id), "部门（组织）已删除");
    }
}