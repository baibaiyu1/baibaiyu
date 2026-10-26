package com.gmd.faq.server.main.common.util;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gmd.faq.server.main.common.enums.ResourceVisibility;

import java.util.Collection;

/**
 * 资源可见性查询支持
 * <p>
 * 为五类资源分页叠加账号、部门负责人和显式授权的可见性条件。
 * <p>
 * 约定：
 * <ul>
 *   <li>租户隔离由 MyBatis-Plus 租户拦截器自动注入，本类不处理租户条件；</li>
 *   <li>收藏档先取收藏 ID，再与可见性条件相交；</li>
 *   <li>部门关系由 Service 按当前租户计算，本类只拼装条件。</li>
 * </ul>
 *
 */
public final class ResourceVisibilitySupport {

    /**
     * 可见性列名（各资源表统一）
     */
    private static final String COLUMN_VISIBILITY = "visibility";

    /**
     * 归属人列名（各资源表统一）
     */
    private static final String COLUMN_OWNER = "owner_id";
    private static final String COLUMN_DEPT = "resource_dept_id";

    private ResourceVisibilitySupport() {
    }

    /**
     * 收藏范围过滤：仅返回给定 ID 集合内的资源
     *
     * @param wrapper 查询包装器
     * @param ids     收藏的资源ID集合
     */
    public static <T> void filterByIds(QueryWrapper<T> wrapper, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            // 无收藏：构造恒假条件，避免退化为全量查询
            wrapper.eq("id", -1L);
            return;
        }
        wrapper.in("id", ids);
    }

    /** 列表与详情共用同一归属、部门及授权语义。 */
    public static <T> void applyAccessPolicy(QueryWrapper<T> wrapper, Long accountId,
                                             Collection<Long> publicDeptIds, Collection<Long> ledDeptIds,
                                             Collection<Long> ancestorDeptIds, Collection<Long> grantedResourceIds) {
        wrapper.and(w -> {
            w.eq("ownership_type", "PERSONAL").eq(COLUMN_OWNER, accountId)
                    .or(n -> n.in("ownership_type", "DEPARTMENT", "PLATFORM").eq("maintainer_id", accountId))
                    .or(n -> n.eq("ownership_type", "PLATFORM").eq(COLUMN_VISIBILITY, "TENANT"));
            if (!ancestorDeptIds.isEmpty()) {
                w.or(n -> n.eq("ownership_type", "DEPARTMENT").in("owning_dept_id", ancestorDeptIds));
            }
            if (!publicDeptIds.isEmpty()) {
                w.or(n -> n.eq("ownership_type", "PERSONAL").eq(COLUMN_VISIBILITY, "TENANT").in(COLUMN_DEPT, publicDeptIds));
                w.or(n -> n.eq("ownership_type", "DEPARTMENT").eq(COLUMN_VISIBILITY, "TENANT").in("owning_dept_id", publicDeptIds));
            }
            if (!ledDeptIds.isEmpty()) {
                w.or(n -> n.eq("ownership_type", "PERSONAL").in(COLUMN_DEPT, ledDeptIds));
                w.or(n -> n.eq("ownership_type", "DEPARTMENT").in("owning_dept_id", ledDeptIds));
            }
            if (!grantedResourceIds.isEmpty()) w.or().in("id", grantedResourceIds);
        });
    }
}
