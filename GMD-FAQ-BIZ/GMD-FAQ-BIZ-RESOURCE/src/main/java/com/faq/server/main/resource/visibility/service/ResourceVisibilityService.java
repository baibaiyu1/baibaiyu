package com.gmd.faq.server.main.resource.visibility.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gmd.faq.server.main.common.vo.ResourceVisibilityVO;
import com.gmd.faq.server.main.common.vo.ResourceGrantVO;
import com.gmd.faq.server.main.common.vo.ResourceGrantAccountVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 资源可见性与收藏 Service
 * <p>
 * 收藏为账号级数据；五类资源按创建时部门、负责人关系与逐账号授权判定。
 * 列表过滤口径见 {@code ResourceVisibilitySupport}。
 *
 */
public interface ResourceVisibilityService {

    /**
     * 切换收藏状态
     *
     * @param resourceType 资源类型：AGENT/WORKFLOW/SKILL/TOOL/KNOWLEDGE/MCP
     * @param resourceId   资源ID
     * @return 切换后的状态：true=已收藏
     */
    Boolean toggleFavorite(String resourceType, String resourceId);

    /**
     * 当前账号在指定资源类型下的收藏ID列表
     *
     * @param resourceType 资源类型
     * @return 资源ID列表
     */
    List<String> listFavoriteIds(String resourceType);

    /**
     * 修改资源可见性
     * <p>
     * 支持 AGENT / TOOL / SKILL / KNOWLEDGE / MCP 五类资源（均已在 V11 迁移中补齐可见性列）；
     * 工作流（WORKFLOW）暂无可见性列，调用会抛出 {@link IllegalArgumentException}。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源ID
     * @param visibility   可见性：PRIVATE / TENANT
     * @return 是否成功
     */
    Boolean updateVisibility(String resourceType, Long resourceId, String visibility);

    /**
     * 批量读取当前账号可见资源的可见性与管理/使用能力。
     * <p>
     * 不可见资源不会返回，避免绕过列表直接探测归属信息。
     *
     * @param resourceType 资源类型
     * @param resourceIds  资源ID集合
     * @return 资源ID → 可见性与归属人（不存在的资源不会出现在结果中）
     */
    Map<String, ResourceVisibilityVO> visibilityMap(String resourceType, Collection<Long> resourceIds);

    /**
     * 为资源列表查询追加可见性过滤（含「我的收藏」档）
     * <p>
     * 各资源域的分页接口统一调用本方法，避免各写一套过滤条件导致口径漂移。
     *
     * @param wrapper      查询包装器
     * @param resourceType 资源类型：AGENT/SKILL/TOOL/KNOWLEDGE/MCP
     * @param scope        personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=我可见的全部
     */
    <T> void applyScope(QueryWrapper<T> wrapper, String resourceType, String scope);

    <T> void applyScope(QueryWrapper<T> wrapper, String resourceType, String scope, String space);

    /** 移交不改变创建记录、可见性、既有使用授权或业务主键。 */
    void transferOwnership(String resourceType, Long resourceId, String ownershipType,
                           Long ownerId, Long departmentId, Long maintainerId);

    /** 当前账号是否可以查看资源；负责人可只读查看所负责部门子树。 */
    boolean canView(String resourceType, Long resourceId);

    /** 当前账号是否可以使用资源；负责人只读权限不自动授予使用权。 */
    boolean canUse(String resourceType, Long resourceId);

    void assertViewable(String resourceType, Long resourceId);

    void assertUsable(String resourceType, Long resourceId);

    /** 无 HTTP 上下文的任务按创建账号检查资源使用权；当前线程须已切入租户上下文。 */
    void assertUsableForAccount(String resourceType, Long resourceId, Long accountId);

    /** 仅资源归属人或管理员可管理逐账号授权。 */
    List<ResourceGrantVO> listGrants(String resourceType, Long resourceId);

    void upsertGrant(String resourceType, Long resourceId, Long accountId, boolean allowUse);

    int upsertGrants(String resourceType, Long resourceId, Collection<Long> accountIds,
                     Collection<Long> departmentIds, boolean allowUse);

    void revokeGrant(String resourceType, Long resourceId, Long accountId);

    void revokeDepartmentGrant(String resourceType, Long resourceId, Long departmentId);

    List<ResourceGrantAccountVO> listGrantCandidates(String resourceType, Long resourceId, String keyword);
}
