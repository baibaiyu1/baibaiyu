package com.gmd.faq.server.main.console.account;

import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.common.dto.TenantCreateRequest;
import com.gmd.faq.server.main.common.dto.TenantSettingsDTO;
import com.gmd.faq.server.main.common.entity.Tenant;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 平台组织（内部租户）Controller
 * <p>
 * 「部门即组织」后，面向用户的「组织管理」界面已下线：组织就是部门树的节点，
 * 组织资料（编码/描述/联系人）与成员都在「系统设置 - 组织与用户」里维护。
 * <p>
 * 本 Controller 只保留 {@code gmd_faq_tenant} 的内部维护能力——它是业务表
 * {@code tenant_id} 与租户拦截器的元数据来源，业务代码不应再直接使用「组织/租户」这一层概念。
 * 原 {@code /tenant/{id}/members} 成员管理接口已删除，成员关系由 {@code account.dept_id} 表达。
 *
 */
@RestController
@RequestMapping("/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    // ==================== 基础 CRUD（内部维护） ====================

    /**
     * 组织列表（内部维护用；前端「组织管理」已下线）
     */
    @GetMapping("/list")
    public R<List<Tenant>> list() {
        return R.data(tenantService.list());
    }

    /**
     * 组织详情
     */
    @GetMapping("/{id}")
    public R<Tenant> detail(@PathVariable("id") Long id) {
        Tenant entity = tenantService.getById(id);
        if (entity == null) {
            return R.fail("组织不存在");
        }
        return R.data(entity);
    }

    /**
     * 创建组织（任何登录用户均可创建，创建者自动成为拥有者）
     */
    @PostMapping
    public R<Tenant> create(@RequestBody TenantCreateRequest request) {
        Tenant tenant = new Tenant();
        tenant.setName(request.getName());
        tenant.setCode(request.getCode());
        tenant.setDescription(request.getDescription());
        tenant.setContactName(request.getContactName());
        tenant.setContactEmail(request.getContactEmail());

        Tenant created = tenantService.createTenant(tenant, UserUtils.getId());
        return R.data(created, "组织创建成功");
    }

    /**
     * 更新组织基本信息
     */
    @PermNeed({"tenant:tenant:edit"})
    @PutMapping("/{id}")
    public R<Boolean> update(@PathVariable("id") Long id, @RequestBody Tenant tenant) {
        tenant.setId(id);
        Long currentTenantId = TenantUtils.getCurrentTenantId();
        if (!Objects.equals(currentTenantId, tenant.getId())) {
            throw new BusinessException("无权限修改");
        }
        return R.data(tenantService.updateById(tenant));
    }

    /**
     * 删除组织
     */
    @PermNeed({"tenant:tenant:remove"})
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable("id") Long id) {
        Long currentTenantId = TenantUtils.getCurrentTenantId();
        if (!Objects.equals(currentTenantId, id)) {
            throw new BusinessException("无权限删除");
        }
        return R.data(tenantService.removeById(id));
    }

    /**
     * 更新组织基本设置
     */
    @PermNeed({"tenant:tenant:edit"})
    @PutMapping("/{id}/settings")
    public R<Tenant> updateSettings(@PathVariable("id") Long tenantId,
                                     @RequestBody TenantSettingsDTO settings) {
        Long currentTenantId = TenantUtils.getCurrentTenantId();
        if (!Objects.equals(currentTenantId, tenantId)) {
            throw new BusinessException("无权限修改");
        }
        return R.data(tenantService.updateSettings(tenantId, settings), "设置已更新");
    }
}
