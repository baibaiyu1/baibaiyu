package com.gmd.faq.server.main.resource.visibility.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.OwnedResource;
import com.gmd.faq.server.main.common.entity.ResourceAccessGrant;
import com.gmd.faq.server.main.common.entity.ResourceDepartmentGrant;
import com.gmd.faq.server.main.common.entity.ResourceFavorite;
import com.gmd.faq.server.main.common.mapper.PermsMapper;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.common.enums.ResourceVisibility;
import com.gmd.faq.server.main.common.util.ResourceDeptSupport;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.ResourceVisibilitySupport;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.ResourceGrantAccountVO;
import com.gmd.faq.server.main.common.vo.ResourceGrantVO;
import com.gmd.faq.server.main.common.vo.ResourceVisibilityVO;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceAccessGrantMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceDepartmentGrantMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceFavoriteMapper;
import com.gmd.faq.server.main.resource.visibility.mapper.ResourceGovernanceMapper;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 五类资源的列表、单条访问与逐账号授权使用同一权限口径。 */
@Service
@RequiredArgsConstructor
public class ResourceVisibilityServiceImpl implements ResourceVisibilityService {

    private final ResourceFavoriteMapper resourceFavoriteMapper;
    private final ResourceGovernanceMapper resourceGovernanceMapper;
    private final ResourceAccessGrantMapper resourceAccessGrantMapper;
    private final ResourceDepartmentGrantMapper resourceDepartmentGrantMapper;
    private final PermsMapper permsMapper;
    private final com.gmd.faq.server.main.common.cluster.core.MessagePublisher messagePublisher;
    private final com.gmd.faq.server.main.common.service.ResourceSpaceService resourceSpaceService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean toggleFavorite(String resourceType, String resourceId) {
        if (UserUtils.isChatKey() || UserUtils.isSk()) {
            throw new IllegalStateException("当前凭证不能收藏资源");
        }
        ApprovalResourceType type = normalizeType(resourceType);
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("资源ID不能为空");
        }
        Long accountId = UserUtils.getId();
        ResourceFavorite existing = resourceFavoriteMapper.selectOne(Wrappers.<ResourceFavorite>lambdaQuery()
                .eq(ResourceFavorite::getResourceType, type.name())
                .eq(ResourceFavorite::getResourceId, resourceId)
                .eq(ResourceFavorite::getAccountId, accountId));
        if (existing != null) {
            resourceFavoriteMapper.deleteById(existing.getId());
            return false;
        }
        if (type != ApprovalResourceType.WORKFLOW) {
            assertViewable(type.name(), parseResourceId(resourceId));
        }
        ResourceFavorite favorite = new ResourceFavorite();
        favorite.setResourceType(type.name());
        favorite.setResourceId(resourceId);
        favorite.setAccountId(accountId);
        resourceFavoriteMapper.insert(favorite);
        return true;
    }

    @Override
    public List<String> listFavoriteIds(String resourceType) {
        if (UserUtils.isChatKey() || UserUtils.isSk()) {
            return List.of();
        }
        ApprovalResourceType type = normalizeType(resourceType);
        List<String> ids = resourceFavoriteMapper.selectList(Wrappers.<ResourceFavorite>lambdaQuery()
                        .eq(ResourceFavorite::getResourceType, type.name())
                        .eq(ResourceFavorite::getAccountId, UserUtils.getId()))
                .stream().map(ResourceFavorite::getResourceId).toList();
        if (ids.isEmpty() || type == ApprovalResourceType.WORKFLOW) {
            return ids;
        }
        List<Long> numericIds = new ArrayList<>();
        for (String id : ids) {
            try {
                numericIds.add(Long.valueOf(id));
            } catch (NumberFormatException ignored) {
                // 历史收藏的非数字 ID 不能绕过资源可见性过滤。
            }
        }
        Set<String> visible = visibilityMap(type.name(), numericIds).keySet();
        return ids.stream().filter(visible::contains).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateVisibility(String resourceType, Long resourceId, String visibility) {
        ApprovalResourceType type = ownedType(resourceType);
        ResourceVisibility target = ResourceVisibility.parse(visibility);
        if (target == null) {
            throw new IllegalArgumentException("可见性取值不合法");
        }
        assertVisibilityChangeable(type, resourceId, target);
        int rows = resourceGovernanceMapper.updateVisibility(tableOf(type), resourceId, target.name(), UserUtils.getResourceAccountId());
        if (rows == 0) {
            throw new IllegalStateException("资源不存在或无权修改可见性");
        }
        publishOwnershipChange(type, resourceId);
        return true;
    }

    @Override
    public Map<String, ResourceVisibilityVO> visibilityMap(String resourceType, Collection<Long> resourceIds) {
        ApprovalResourceType type = ownedType(resourceType);
        if (resourceIds == null || resourceIds.isEmpty() || UserUtils.isChatKey() || UserUtils.isSk()) {
            return Map.of();
        }
        List<Long> ids = resourceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        PolicyContext context = policyContext();
        if (context.accountId() == null && !UserUtils.isAdmin()) {
            return Map.of();
        }
        Map<Long, ResourceAccessGrant> grants = grantsFor(type, ids, context);
        Map<String, ResourceVisibilityVO> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = type == ApprovalResourceType.AGENT
                ? resourceGovernanceMapper.selectAgentVisibility(ids)
                : resourceGovernanceMapper.selectVisibility(tableOf(type), ids);
        for (Map<String, Object> row : rows) {
            Long id = ResourceDeptSupport.toLong(row.get("id"));
            if (id == null || !mayView(row, context, grants.get(id))) {
                continue;
            }
            ResourceVisibilityVO vo = new ResourceVisibilityVO();
            vo.setVisibility(value(row, "visibility"));
            vo.setOwnerId(ResourceDeptSupport.toLong(row.get("owner_id")));
            vo.setCanUse(mayUse(type, row, context, grants.get(id)));
            vo.setCanManage(canManage(type, row, context));
            OwnedResourceRef ownership = ownership(row);
            vo.setOwnershipType(ResourceOwnershipSupport.ownershipType(ownership));
            vo.setOwningDeptId(ownership.owningDeptId());
            vo.setMaintainerId(ownership.maintainerId());
            vo.setCanTransfer(canTransfer(type, row, context));
            vo.setSpaceName(switch (vo.getOwnershipType()) {
                case "DEPARTMENT" -> "部门资产";
                case "PLATFORM" -> "平台公共";
                default -> "个人资产";
            });
            vo.setCanChangeVisibility(canManage(type, row, context));
            result.put(id.toString(), vo);
        }
        return result;
    }

    @Override
    public <T> void applyScope(QueryWrapper<T> wrapper, String resourceType, String scope) {
        applyScope(wrapper, resourceType, scope, null);
    }

    @Override
    public <T> void applyScope(QueryWrapper<T> wrapper, String resourceType, String scope, String space) {
        ApprovalResourceType type = ownedType(resourceType);
        PolicyContext context = policyContext();
        if (UserUtils.isChatKey() || UserUtils.isSk()
                || (context.accountId() == null && !UserUtils.isAdmin())) {
            wrapper.eq("id", -1L);
            return;
        }
        if (space != null && !space.isBlank() && !"all".equals(space)) {
            resourceSpaceService.assertSelectable(space);
            if ("personal".equals(space)) wrapper.eq("ownership_type", "PERSONAL").eq("owner_id", context.accountId());
            else if ("platform".equals(space)) wrapper.eq("ownership_type", "PLATFORM");
            else if (space.startsWith("department:")) wrapper.eq("ownership_type", "DEPARTMENT")
                    .eq("owning_dept_id", Long.valueOf(space.substring("department:".length())));
            else throw new IllegalArgumentException("空间不合法");
        }
        String selected = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        switch (selected) {
            case "personal" -> wrapper.eq("owner_id", context.accountId());
            case "public" -> wrapper.eq("visibility", ResourceVisibility.TENANT.name());
            case "shared" -> wrapper.and(w -> w.ne("ownership_type", "PERSONAL").or().ne("owner_id", context.accountId()));
            case "favorite" -> ResourceVisibilitySupport.filterByIds(wrapper, listFavoriteIds(type.name()));
            default -> { }
        }
        if (!UserUtils.isAdmin()) {
            Set<Long> grantIds = new LinkedHashSet<>(resourceAccessGrantMapper.selectList(Wrappers.<ResourceAccessGrant>lambdaQuery()
                            .eq(ResourceAccessGrant::getResourceType, type.name())
                            .eq(ResourceAccessGrant::getAccountId, context.accountId()))
                    .stream().map(ResourceAccessGrant::getResourceId).toList());
            if (!context.ancestorDeptIds().isEmpty()) {
                resourceDepartmentGrantMapper.selectList(Wrappers.<ResourceDepartmentGrant>lambdaQuery()
                                .eq(ResourceDepartmentGrant::getResourceType, type.name())
                                .in(ResourceDepartmentGrant::getDepartmentId, context.ancestorDeptIds()))
                        .forEach(grant -> grantIds.add(grant.getResourceId()));
            }
            ResourceVisibilitySupport.applyAccessPolicy(wrapper, context.accountId(),
                    context.publicDeptIds(), context.ledDeptIds(), context.ancestorDeptIds(), grantIds);
        }
    }

    @Override
    public boolean canView(String resourceType, Long resourceId) {
        ApprovalResourceType type = ownedType(resourceType);
        if (resourceId == null || UserUtils.isChatKey() || UserUtils.isSk()) {
            return false;
        }
        Map<String, Object> row = resourceRow(type, resourceId);
        if (row == null) {
            return false;
        }
        PolicyContext context = policyContext();
        return mayView(row, context, effectiveGrantFor(type, resourceId, context));
    }

    @Override
    public boolean canUse(String resourceType, Long resourceId) {
        ApprovalResourceType type = ownedType(resourceType);
        if (resourceId == null) {
            return false;
        }
        if (UserUtils.isChatKey()) {
            return type == ApprovalResourceType.AGENT && resourceId.equals(UserUtils.getBoundAgentId())
                    && agentRunnable(resourceRow(type, resourceId), null);
        }
        Map<String, Object> row = resourceRow(type, resourceId);
        if (row == null) {
            return false;
        }
        PolicyContext context = policyContext();
        return mayUse(type, row, context, effectiveGrantFor(type, resourceId, context));
    }

    @Override
    public void assertViewable(String resourceType, Long resourceId) {
        if (!canView(resourceType, resourceId)) {
            throw new IllegalStateException("资源不存在或无权查看");
        }
    }

    @Override
    public void assertUsable(String resourceType, Long resourceId) {
        if (!canUse(resourceType, resourceId)) {
            throw new IllegalStateException("资源不存在或无权使用");
        }
    }

    @Override
    public void assertUsableForAccount(String resourceType, Long resourceId, Long accountId) {
        ApprovalResourceType type = ownedType(resourceType);
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (resourceId == null || accountId == null || tenantId == null
                || resourceGovernanceMapper.countAccountInTenant(accountId, tenantId) == 0) {
            throw new IllegalStateException("资源不存在或无权使用");
        }
        Map<String, Object> row = resourceRow(type, resourceId);
        if (row == null) {
            throw new IllegalStateException("资源不存在或无权使用");
        }
        PolicyContext context = policyContext(accountId, tenantId);
        Long deptId = resourceGovernanceMapper.selectAccountDeptId(accountId);
        List<Long> ancestors = deptId == null ? List.of() : new ArrayList<>(context.ancestorDeptIds());
        boolean admin = permsMapper.selectPerms(accountId, tenantId, ancestors).contains(UserUtils.PERM_ADMIN);
        if (!admin && !mayUse(type, row, context, effectiveGrantFor(type, resourceId, context), false)) {
            throw new IllegalStateException("资源不存在或无权使用");
        }
    }

    @Override
    public List<ResourceGrantVO> listGrants(String resourceType, Long resourceId) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "查看授权");
        List<ResourceGrantVO> grants = new ArrayList<>();
        resourceGovernanceMapper.selectGrantRows(type.name(), resourceId).forEach(row -> {
            ResourceGrantVO vo = new ResourceGrantVO();
            vo.setAccountId(ResourceDeptSupport.toLong(row.get("account_id")));
            vo.setAllowUse(asBoolean(row.get("allow_use")));
            vo.setNickname(value(row, "nickname"));
            vo.setUsername(value(row, "username"));
            grants.add(vo);
        });
        resourceGovernanceMapper.selectDepartmentGrantRows(type.name(), resourceId).forEach(row -> {
            ResourceGrantVO vo = new ResourceGrantVO();
            vo.setDepartmentId(ResourceDeptSupport.toLong(row.get("department_id")));
            vo.setDepartmentName(value(row, "departmentName"));
            vo.setAllowUse(asBoolean(row.get("allow_use")));
            grants.add(vo);
        });
        return grants;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsertGrant(String resourceType, Long resourceId, Long accountId, boolean allowUse) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "设置授权");
        if (accountId == null || accountId <= 0 || UserUtils.getTenantId() == null
                || resourceGovernanceMapper.countAccountInTenant(accountId, UserUtils.getTenantId()) == 0) {
            throw new IllegalArgumentException("被授权账号不属于当前组织");
        }
        upsertAccountGrant(type, resourceId, accountId, allowUse);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int upsertGrants(String resourceType, Long resourceId, Collection<Long> accountIds,
                            Collection<Long> departmentIds, boolean allowUse) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "设置授权");
        Set<Long> accounts = new LinkedHashSet<>(accountIds == null ? List.of() : accountIds);
        Set<Long> departments = new LinkedHashSet<>(departmentIds == null ? List.of() : departmentIds);
        if (accounts.isEmpty() && departments.isEmpty()) {
            throw new IllegalArgumentException("请选择账号或部门");
        }
        if (accounts.size() > 500 || departments.size() > 100) {
            throw new IllegalArgumentException("一次授权的账号或部门过多");
        }
        Map<Long, Long> parentOf = ResourceDeptSupport.parentMap(resourceGovernanceMapper.selectDeptIdAndParent());
        for (Long accountId : accounts) {
            if (accountId == null || accountId <= 0 || UserUtils.getTenantId() == null
                    || resourceGovernanceMapper.countAccountInTenant(accountId, UserUtils.getTenantId()) == 0) {
                throw new IllegalArgumentException("被授权账号不属于当前组织");
            }
        }
        for (Long departmentId : departments) {
            if (departmentId == null || !parentOf.containsKey(departmentId)) {
                throw new IllegalArgumentException("被授权部门不属于当前组织");
            }
        }
        accounts.forEach(accountId -> upsertAccountGrant(type, resourceId, accountId, allowUse));
        departments.forEach(departmentId -> upsertDepartmentGrant(type, resourceId, departmentId, allowUse));
        return accounts.size() + departments.size();
    }

    private void upsertAccountGrant(ApprovalResourceType type, Long resourceId, Long accountId, boolean allowUse) {
        ResourceAccessGrant existing = grantFor(type, resourceId, accountId);
        if (existing == null) {
            ResourceAccessGrant grant = new ResourceAccessGrant();
            grant.setTenantId(UserUtils.getTenantId());
            grant.setResourceType(type.name());
            grant.setResourceId(resourceId);
            grant.setAccountId(accountId);
            grant.setAllowUse(allowUse);
            resourceAccessGrantMapper.insert(grant);
        } else {
            existing.setAllowUse(allowUse);
            resourceAccessGrantMapper.updateById(existing);
        }
    }

    private void upsertDepartmentGrant(ApprovalResourceType type, Long resourceId,
                                       Long departmentId, boolean allowUse) {
        ResourceDepartmentGrant existing = resourceDepartmentGrantMapper.selectOne(
                Wrappers.<ResourceDepartmentGrant>lambdaQuery()
                        .eq(ResourceDepartmentGrant::getResourceType, type.name())
                        .eq(ResourceDepartmentGrant::getResourceId, resourceId)
                        .eq(ResourceDepartmentGrant::getDepartmentId, departmentId));
        if (existing == null) {
            ResourceDepartmentGrant grant = new ResourceDepartmentGrant();
            grant.setTenantId(UserUtils.getTenantId());
            grant.setResourceType(type.name());
            grant.setResourceId(resourceId);
            grant.setDepartmentId(departmentId);
            grant.setAllowUse(allowUse);
            resourceDepartmentGrantMapper.insert(grant);
        } else {
            existing.setAllowUse(allowUse);
            resourceDepartmentGrantMapper.updateById(existing);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeGrant(String resourceType, Long resourceId, Long accountId) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "撤销授权");
        if (accountId == null) {
            throw new IllegalArgumentException("账号ID不能为空");
        }
        resourceAccessGrantMapper.delete(Wrappers.<ResourceAccessGrant>lambdaQuery()
                .eq(ResourceAccessGrant::getResourceType, type.name())
                .eq(ResourceAccessGrant::getResourceId, resourceId)
                .eq(ResourceAccessGrant::getAccountId, accountId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeDepartmentGrant(String resourceType, Long resourceId, Long departmentId) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "撤销授权");
        if (departmentId == null) {
            throw new IllegalArgumentException("部门ID不能为空");
        }
        resourceDepartmentGrantMapper.delete(Wrappers.<ResourceDepartmentGrant>lambdaQuery()
                .eq(ResourceDepartmentGrant::getResourceType, type.name())
                .eq(ResourceDepartmentGrant::getResourceId, resourceId)
                .eq(ResourceDepartmentGrant::getDepartmentId, departmentId));
    }

    @Override
    public List<ResourceGrantAccountVO> listGrantCandidates(String resourceType, Long resourceId, String keyword) {
        ApprovalResourceType type = ownedType(resourceType);
        assertManageable(type, resourceId, "查看可授权账号");
        String search = keyword == null ? null : keyword.trim();
        if (search != null && search.length() > 40) {
            throw new IllegalArgumentException("搜索词过长");
        }
        return resourceGovernanceMapper.selectGrantCandidates(UserUtils.getTenantId(), search).stream().map(row -> {
            ResourceGrantAccountVO vo = new ResourceGrantAccountVO();
            vo.setId(ResourceDeptSupport.toLong(row.get("id")));
            vo.setNickname(value(row, "nickname"));
            vo.setUsername(value(row, "username"));
            vo.setDeptName(value(row, "deptName"));
            return vo;
        }).toList();
    }

    private Map<String, Object> resourceRow(ApprovalResourceType type, Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        List<Map<String, Object>> rows = type == ApprovalResourceType.AGENT
                ? resourceGovernanceMapper.selectAgentAccessRow(resourceId)
                : resourceGovernanceMapper.selectOwnerId(tableOf(type), resourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PolicyContext policyContext() {
        Long accountId = UserUtils.getResourceAccountId();
        return policyContext(accountId, UserUtils.getTenantId());
    }

    private PolicyContext policyContext(Long accountId, Long tenantId) {
        if (accountId == null || tenantId == null) {
            return new PolicyContext(null, Set.of(), Set.of(), Set.of());
        }
        Map<Long, Long> parentOf = ResourceDeptSupport.parentMap(resourceGovernanceMapper.selectDeptIdAndParent());
        Set<Long> publicDeptIds = new LinkedHashSet<>();
        Set<Long> ancestors = new LinkedHashSet<>();
        Long deptId = resourceGovernanceMapper.selectAccountDeptId(accountId);
        if (deptId != null && parentOf.containsKey(deptId)) {
            ancestors.addAll(ResourceDeptSupport.collectAncestors(parentOf, deptId));
            publicDeptIds.addAll(ancestors);
            publicDeptIds.addAll(ResourceDeptSupport.collectSubtree(parentOf, deptId));
        }
        Set<Long> ledDeptIds = new LinkedHashSet<>();
        for (Long ledDeptId : resourceGovernanceMapper.selectLedDepartmentIds(accountId)) {
            if (parentOf.containsKey(ledDeptId)) {
                ledDeptIds.addAll(ResourceDeptSupport.collectSubtree(parentOf, ledDeptId));
            }
        }
        return new PolicyContext(accountId, publicDeptIds, ledDeptIds, ancestors);
    }

    private boolean mayView(Map<String, Object> row, PolicyContext context, ResourceAccessGrant grant) {
        if (UserUtils.isAdmin()) return true;
        if (context.accountId() == null) return false;
        OwnedResourceRef ref = ownership(row);
        String type = ResourceOwnershipSupport.ownershipType(ref);
        boolean manager = ResourceOwnershipSupport.canManage(ref, context.accountId(), false, context.ledDeptIds());
        boolean shared = "TENANT".equals(ref.visibility());
        return manager || grant != null || switch (type) {
            case "PLATFORM" -> shared;
            case "DEPARTMENT" -> ref.owningDeptId() != null && (context.ancestorDeptIds().contains(ref.owningDeptId())
                    || shared && context.publicDeptIds().contains(ref.owningDeptId()));
            default -> ref.resourceDeptId() != null && (context.ledDeptIds().contains(ref.resourceDeptId())
                    || shared && context.publicDeptIds().contains(ref.resourceDeptId()));
        };
    }

    private boolean mayUse(ApprovalResourceType type, Map<String, Object> row, PolicyContext context, ResourceAccessGrant grant) {
        return mayUse(type, row, context, grant, UserUtils.isAdmin());
    }

    private boolean mayUse(ApprovalResourceType type, Map<String, Object> row, PolicyContext context, ResourceAccessGrant grant, boolean admin) {
        if (admin) return true;
        if (context.accountId() == null) return false;
        OwnedResourceRef ref = ownership(row);
        boolean manager = ResourceOwnershipSupport.canManage(ref, context.accountId(), false, context.ledDeptIds());
        boolean shared = "TENANT".equals(ref.visibility());
        boolean allowed = manager || grant != null && Boolean.TRUE.equals(grant.getAllowUse()) || switch (ResourceOwnershipSupport.ownershipType(ref)) {
            case "PLATFORM" -> shared;
            case "DEPARTMENT" -> ref.owningDeptId() != null && (context.ancestorDeptIds().contains(ref.owningDeptId())
                    || shared && context.publicDeptIds().contains(ref.owningDeptId()));
            default -> shared && ref.resourceDeptId() != null && context.publicDeptIds().contains(ref.resourceDeptId());
        };
        return allowed && (type != ApprovalResourceType.AGENT || manager || agentRunnable(row, context.accountId()));
    }

    private boolean agentRunnable(Map<String, Object> row, Long accountId) {
        return row != null && (accountId != null && accountId.equals(ResourceDeptSupport.toLong(row.get("owner_id")))
                || asBoolean(row.get("enabled")) && asBoolean(row.get("published")));
    }

    private boolean canManage(ApprovalResourceType type, Map<String, Object> row, PolicyContext context) {
        return UserUtils.isAdmin() || hasDomainEdit(type) && ResourceOwnershipSupport.canManage(
                ownership(row), context.accountId(), false, context.ledDeptIds());
    }

    private boolean canTransfer(ApprovalResourceType type, Map<String, Object> row, PolicyContext context) {
        if (UserUtils.isAdmin()) return true;
        if (!hasDomainEdit(type)) return false;
        OwnedResourceRef ref = ownership(row);
        return switch (ResourceOwnershipSupport.ownershipType(ref)) {
            case "PERSONAL" -> context.accountId() != null && context.accountId().equals(ref.ownerId());
            case "DEPARTMENT" -> context.ledDeptIds().contains(ref.owningDeptId());
            default -> false;
        };
    }

    private void assertManageable(ApprovalResourceType type, Long resourceId, String action) {
        Map<String, Object> row = resourceRow(type, resourceId);
        if (row == null || !canManage(type, row, policyContext())) {
            throw new IllegalStateException("资源不存在或无权" + action);
        }
    }

    private void assertVisibilityChangeable(ApprovalResourceType type, Long resourceId, ResourceVisibility target) {
        List<Map<String, Object>> rows = resourceGovernanceMapper.lockOwnership(tableOf(type), resourceId);
        if (rows.isEmpty() || !canManage(type, rows.getFirst(), policyContext())) {
            throw new IllegalStateException("资源不存在或无权修改可见性");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferOwnership(String resourceType, Long resourceId, String ownershipType,
                                  Long ownerId, Long departmentId, Long maintainerId) {
        ApprovalResourceType type = ownedType(resourceType);
        List<Map<String, Object>> rows = resourceGovernanceMapper.lockOwnership(tableOf(type), resourceId);
        if (rows.isEmpty()) throw new IllegalArgumentException("资源不存在");
        PolicyContext context = policyContext();
        Map<String, Object> row = rows.getFirst();
        if (!canTransfer(type, row, context)) throw new IllegalStateException("无权移交资源；部门资产由负责人或管理员移交");
        String sourceType = ResourceOwnershipSupport.ownershipType(ownership(row));
        Long targetOwner = null;
        Long targetDepartment = null;
        Long targetMaintainer = null;
        switch (ownershipType == null ? "" : ownershipType) {
            case "PERSONAL" -> {
                if (!UserUtils.isAdmin() && !"PERSONAL".equals(sourceType)) throw new IllegalStateException("部门资产不能转为个人资产");
                targetOwner = ownerId == null ? context.accountId() : ownerId;
                requireAccount(targetOwner);
            }
            case "DEPARTMENT" -> {
                if (departmentId == null) throw new IllegalArgumentException("请选择资产所属部门");
                resourceSpaceService.assertSelectable("department:" + departmentId);
                if (!UserUtils.isAdmin() && !"PERSONAL".equals(sourceType) && !context.ledDeptIds().contains(departmentId)) {
                    throw new IllegalStateException("只能移交到管理范围内的部门");
                }
                targetDepartment = departmentId;
                targetMaintainer = maintainerId;
                if (targetMaintainer != null) {
                    requireAccount(targetMaintainer);
                    Map<Long, Long> parents = ResourceDeptSupport.parentMap(resourceGovernanceMapper.selectDeptIdAndParent());
                    Long maintainerDept = resourceGovernanceMapper.selectAccountDeptId(targetMaintainer);
                    if (!ResourceDeptSupport.collectSubtree(parents, departmentId).contains(maintainerDept)) {
                        throw new IllegalArgumentException("部门资产维护人必须属于该部门或下级部门");
                    }
                }
            }
            case "PLATFORM" -> {
                if (!UserUtils.isAdmin()) throw new IllegalStateException("只有管理员可将资源转为平台资产");
                targetMaintainer = maintainerId;
                if (targetMaintainer != null) requireAccount(targetMaintainer);
            }
            default -> throw new IllegalArgumentException("资产归属类型不合法");
        }
        if (resourceGovernanceMapper.transferOwnership(tableOf(type), resourceId, ownershipType,
                targetOwner, targetDepartment, targetMaintainer, context.accountId()) != 1) {
            throw new IllegalStateException("资源移交失败");
        }
        publishOwnershipChange(type, resourceId);
    }

    private void publishOwnershipChange(ApprovalResourceType type, Long id) {
        if (type == ApprovalResourceType.AGENT) messagePublisher.publishAfterCommit(
                com.gmd.faq.server.main.common.consts.RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(id));
    }

    private void requireAccount(Long id) {
        if (id == null || resourceGovernanceMapper.countAccountInTenant(id, UserUtils.getTenantId()) == 0) {
            throw new IllegalArgumentException("目标账号不存在、已停用或不属于当前平台");
        }
    }

    private static OwnedResourceRef ownership(Map<String, Object> row) {
        return new OwnedResourceRef(ResourceDeptSupport.toLong(row.get("owner_id")),
                ResourceDeptSupport.toLong(row.get("resource_dept_id")), value(row, "visibility"), value(row, "ownership_type"),
                ResourceDeptSupport.toLong(row.get("owning_dept_id")), ResourceDeptSupport.toLong(row.get("maintainer_id")));
    }

    private boolean hasDomainEdit(ApprovalResourceType type) {
        return UserUtils.hasPerm(switch (type) {
            case AGENT -> "agent:definition:edit";
            case TOOL -> "tool:config:edit";
            case SKILL -> "skill:package:edit";
            case KNOWLEDGE -> "knowledge:config:edit";
            case MCP -> "mcp:server:edit";
            case WORKFLOW -> throw new IllegalArgumentException("工作流暂不支持逐账号授权");
        });
    }

    private ResourceAccessGrant grantFor(ApprovalResourceType type, Long resourceId, Long accountId) {
        if (accountId == null) {
            return null;
        }
        return resourceAccessGrantMapper.selectOne(Wrappers.<ResourceAccessGrant>lambdaQuery()
                .eq(ResourceAccessGrant::getResourceType, type.name())
                .eq(ResourceAccessGrant::getResourceId, resourceId)
                .eq(ResourceAccessGrant::getAccountId, accountId));
    }

    private ResourceAccessGrant effectiveGrantFor(ApprovalResourceType type, Long resourceId,
                                                  PolicyContext context) {
        ResourceAccessGrant accountGrant = grantFor(type, resourceId, context.accountId());
        if (context.ancestorDeptIds().isEmpty()) {
            return accountGrant;
        }
        List<ResourceDepartmentGrant> departmentGrants = resourceDepartmentGrantMapper.selectList(
                Wrappers.<ResourceDepartmentGrant>lambdaQuery()
                        .eq(ResourceDepartmentGrant::getResourceType, type.name())
                        .eq(ResourceDepartmentGrant::getResourceId, resourceId)
                        .in(ResourceDepartmentGrant::getDepartmentId, context.ancestorDeptIds()));
        return mergeGrants(accountGrant, departmentGrants);
    }

    private Map<Long, ResourceAccessGrant> grantsFor(ApprovalResourceType type, List<Long> resourceIds,
                                                     PolicyContext context) {
        if (context.accountId() == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ResourceAccessGrant> result = new LinkedHashMap<>();
        for (ResourceAccessGrant grant : resourceAccessGrantMapper.selectList(Wrappers.<ResourceAccessGrant>lambdaQuery()
                .eq(ResourceAccessGrant::getResourceType, type.name())
                .eq(ResourceAccessGrant::getAccountId, context.accountId())
                .in(ResourceAccessGrant::getResourceId, resourceIds))) {
            result.put(grant.getResourceId(), grant);
        }
        if (!context.ancestorDeptIds().isEmpty()) {
            for (ResourceDepartmentGrant grant : resourceDepartmentGrantMapper.selectList(
                    Wrappers.<ResourceDepartmentGrant>lambdaQuery()
                            .eq(ResourceDepartmentGrant::getResourceType, type.name())
                            .in(ResourceDepartmentGrant::getResourceId, resourceIds)
                            .in(ResourceDepartmentGrant::getDepartmentId, context.ancestorDeptIds()))) {
                result.put(grant.getResourceId(), mergeGrants(result.get(grant.getResourceId()), List.of(grant)));
            }
        }
        return result;
    }

    private ResourceAccessGrant mergeGrants(ResourceAccessGrant accountGrant,
                                            List<ResourceDepartmentGrant> departmentGrants) {
        if (departmentGrants.isEmpty()) {
            return accountGrant;
        }
        ResourceAccessGrant effective = new ResourceAccessGrant();
        effective.setAllowUse((accountGrant != null && Boolean.TRUE.equals(accountGrant.getAllowUse()))
                || departmentGrants.stream().anyMatch(grant -> Boolean.TRUE.equals(grant.getAllowUse())));
        return effective;
    }

    private ApprovalResourceType ownedType(String resourceType) {
        ApprovalResourceType type = normalizeType(resourceType);
        if (type == ApprovalResourceType.WORKFLOW) {
            throw new IllegalArgumentException("工作流暂不支持资源可见性");
        }
        return type;
    }

    private ApprovalResourceType normalizeType(String resourceType) {
        ApprovalResourceType type = ApprovalResourceType.parse(resourceType);
        if (type == null) {
            throw new IllegalArgumentException("资源类型不合法");
        }
        return type;
    }

    private String tableOf(ApprovalResourceType type) {
        return switch (type) {
            case AGENT -> TableConst.AGENT;
            case TOOL -> TableConst.TOOL;
            case SKILL -> TableConst.SKILL;
            case KNOWLEDGE -> TableConst.KNOWLEDGE;
            case MCP -> TableConst.MCP;
            case WORKFLOW -> throw new IllegalArgumentException("工作流暂不支持资源可见性");
        };
    }

    private Long parseResourceId(String resourceId) {
        try {
            return Long.valueOf(resourceId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("资源ID不合法", e);
        }
    }

    private static String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && !"0".equals(String.valueOf(value));
    }

    private record PolicyContext(Long accountId, Set<Long> publicDeptIds,
                                 Set<Long> ledDeptIds, Set<Long> ancestorDeptIds) {
    }

    private record OwnedResourceRef(Long ownerId, Long resourceDeptId, String visibility, String ownershipType,
                                    Long owningDeptId, Long maintainerId) implements OwnedResource {
        @Override public String getOwnershipType() { return ownershipType; }
        @Override public Long getOwningDeptId() { return owningDeptId; }
        @Override public Long getMaintainerId() { return maintainerId; }
        @Override public Long getOwnerId() { return ownerId; }
        @Override public Long getResourceDeptId() { return resourceDeptId; }
        @Override public String getVisibility() { return visibility; }
        @Override public void setOwnerId(Long ignored) { throw new UnsupportedOperationException(); }
        @Override public void setResourceDeptId(Long ignored) { throw new UnsupportedOperationException(); }
        @Override public void setVisibility(String ignored) { throw new UnsupportedOperationException(); }
        @Override public void setOwnershipType(String ignored) { throw new UnsupportedOperationException(); }
        @Override public void setOwningDeptId(Long ignored) { throw new UnsupportedOperationException(); }
        @Override public void setMaintainerId(Long ignored) { throw new UnsupportedOperationException(); }
    }
}
