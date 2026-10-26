package com.gmd.faq.server.main.common.util;

import com.gmd.faq.server.main.common.entity.OwnedResource;
import com.gmd.faq.server.main.common.service.ResourceSpaceService;
import java.util.Collection;
import java.util.List;

/** 五类资源共用归属规则；可见性不改变维护资格。 */
public final class ResourceOwnershipSupport {
    private ResourceOwnershipSupport() { }
    public static final String SPACE_HEADER = "X-Resource-Space";

    /** 客户端只能选择空间，实际归属、维护人与可见性均由服务端初始化。 */
    public static void initOwnership(OwnedResource resource) {
        if (resource == null) return;
        if (UserUtils.isChatKey() || UserUtils.isSk() || UserUtils.getResourceAccountId() == null) {
            throw new IllegalStateException("只有登录账号可以创建资源");
        }
        String space = WebUtils.getRequest() == null ? null : WebUtils.getRequest().getHeader(SPACE_HEADER);
        if (space == null || space.isBlank() || "personal".equals(space)) {
            resource.setOwnerId(UserUtils.getResourceAccountId());
            resource.setVisibility("PRIVATE");
            resource.setOwnershipType("PERSONAL");
            resource.setOwningDeptId(null);
            resource.setMaintainerId(null);
        } else {
            BeanUtils.getBean(ResourceSpaceService.class).initialize(resource, space);
        }
        resource.setResourceDeptId(UserUtils.getUserDetail() == null ? null : UserUtils.getUserDetail().getDeptId());
    }

    public static String ownershipType(OwnedResource resource) {
        String type = resource.getOwnershipType();
        return type == null || type.isBlank() ? (resource.getOwnerId() == null ? "PLATFORM" : "PERSONAL") : type;
    }

    public static boolean canManage(OwnedResource resource, Long accountId, boolean admin, Collection<Long> managedDepts) {
        if (resource == null || accountId == null) return false;
        if (admin) return true;
        return switch (ownershipType(resource)) {
            case "PERSONAL" -> accountId.equals(resource.getOwnerId());
            case "DEPARTMENT" -> resource.getOwningDeptId() != null &&
                    (accountId.equals(resource.getMaintainerId()) || managedDepts.contains(resource.getOwningDeptId()));
            case "PLATFORM" -> accountId.equals(resource.getMaintainerId());
            default -> false;
        };
    }

    public static <T extends OwnedResource> T assertManageable(T resource, String actionTip) {
        if (resource == null) throw new IllegalStateException("资源不存在");
        if (!isManageable(resource)) {
            throw new IllegalStateException("只有资源归属人、指定维护人、负责该部门的负责人或管理员可" + actionTip);
        }
        return resource;
    }

    public static boolean isManageable(OwnedResource resource) {
        if (resource == null) return false;
        Collection<Long> departments = "DEPARTMENT".equals(ownershipType(resource)) && !UserUtils.isAdmin()
                ? BeanUtils.getBean(ResourceSpaceService.class).managedDepartments() : List.of();
        return canManage(resource, UserUtils.getResourceAccountId(), UserUtils.isAdmin(), departments);
    }

    public static boolean isSharedPool(OwnedResource resource) {
        return resource != null && "PLATFORM".equals(ownershipType(resource));
    }
}
