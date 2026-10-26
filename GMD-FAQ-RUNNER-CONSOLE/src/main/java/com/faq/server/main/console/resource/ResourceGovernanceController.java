package com.gmd.faq.server.main.console.resource;

import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.common.vo.ResourceVisibilityVO;
import com.gmd.faq.server.main.common.vo.ResourceGrantAccountVO;
import com.gmd.faq.server.main.common.vo.ResourceGrantVO;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资源治理 Controller
 * <p>
 * 承载资源级能力：收藏（账号级）、可见性（仅归属人或管理员可改）、Fork（复制归自己的副本）。
 * <p>
 * <b>资源级判权的落点</b>：列表的可见范围由 {@code ResourceVisibilitySupport} 统一实现；
 * 单条资源的改 / 删 / 可见性切换由各域 Service 调 {@code ResourceOwnershipSupport.assertManageable}
 * 校验归属，Controller 只负责入口权限点。
 *
 */
@RestController
@RequestMapping("/resource")
@RequiredArgsConstructor
public class ResourceGovernanceController {

    private final ResourceVisibilityService resourceVisibilityService;
    private final AgentDefinitionService agentDefinitionService;
    private final com.gmd.faq.server.main.common.service.ResourceSpaceService resourceSpaceService;

    @GetMapping("/spaces")
    @PermNeed({"system:admin", "agent:definition:list", "tool:config:list", "skill:package:list",
            "knowledge:config:list", "mcp:server:list"})
    public R<List<com.gmd.faq.server.main.common.vo.ResourceSpaceVO>> spaces() {
        return R.data(resourceSpaceService.spaces());
    }

    @PutMapping("/ownership")
    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    public R<Boolean> transferOwnership(@RequestParam("resourceType") String resourceType,
                                       @RequestParam("resourceId") Long resourceId,
                                       @RequestBody OwnershipRequest request) {
        try {
            resourceVisibilityService.transferOwnership(resourceType, resourceId, request.ownershipType(),
                    request.ownerId(), request.departmentId(), request.maintainerId());
            return R.data(true, "资源归属已更新");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    public record OwnershipRequest(String ownershipType, Long ownerId, Long departmentId, Long maintainerId) { }

    /**
     * 当前账号在指定资源类型下的收藏ID列表
     */
    @GetMapping("/favorite/ids")
    public R<List<String>> favoriteIds(@RequestParam("resourceType") String resourceType) {
        try {
            return R.data(resourceVisibilityService.listFavoriteIds(resourceType));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 切换收藏状态
     *
     * @return true=已收藏，false=已取消收藏
     */
    @PostMapping("/favorite/toggle")
    public R<Boolean> toggleFavorite(@RequestParam("resourceType") String resourceType,
                                     @RequestParam("resourceId") String resourceId) {
        try {
            Boolean favorited = resourceVisibilityService.toggleFavorite(resourceType, resourceId);
            return R.data(favorited, Boolean.TRUE.equals(favorited) ? "已收藏" : "已取消收藏");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 修改资源可见性（仅归属人本人或组织管理员）
     * <p>
     * 支持 AGENT / TOOL / SKILL / KNOWLEDGE / MCP；工作流无可见性列，返回失败提示。
     * <p>
     * <b>入口权限按资源域判定</b>：只有在该域有编辑权的人才能切自己的资源可见性，
     * {@code viewer}（只读角色）不在此列；「是不是归属人本人」由 Service 层
     * {@code ResourceOwnershipSupport.assertManageable} 兜底——原先统一要求管理员级的
     * {@code resource:visibility:manage}，会让普通用户连自己资源的「设为部门共享」都点不动。
     */
    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @PutMapping("/visibility")
    public R<Boolean> updateVisibility(@RequestParam("resourceType") String resourceType,
                                      @RequestParam("resourceId") Long resourceId,
                                      @RequestParam("visibility") String visibility) {
        try {
            return R.data(resourceVisibilityService.updateVisibility(resourceType, resourceId, visibility), "可见性已更新");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 批量读取资源可见性与归属人
     * <p>
     * 各资源域列表接口不返回 visibility / owner_id（VO 无该字段），前端卡片列表加载后调用本接口补读，
     * 据此展示可见性状态，并只对归属人（或管理员）展示切换与改/删入口。
     *
     * @param resourceType 资源类型
     * @param resourceIds  资源ID集合（逗号分隔）
     * @return 资源ID → 可见性与归属人
     */
    @GetMapping("/visibility")
    public R<Map<String, ResourceVisibilityVO>> visibility(@RequestParam("resourceType") String resourceType,
                                                           @RequestParam("resourceIds") List<Long> resourceIds) {
        try {
            return R.data(resourceVisibilityService.visibilityMap(resourceType, resourceIds));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @GetMapping("/grants")
    public R<List<ResourceGrantVO>> grants(@RequestParam("resourceType") String resourceType,
                                           @RequestParam("resourceId") Long resourceId) {
        try {
            return R.data(resourceVisibilityService.listGrants(resourceType, resourceId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @GetMapping("/grants/accounts")
    public R<List<ResourceGrantAccountVO>> grantAccounts(@RequestParam("resourceType") String resourceType,
                                                          @RequestParam("resourceId") Long resourceId,
                                                          @RequestParam(value = "keyword", required = false) String keyword) {
        try {
            return R.data(resourceVisibilityService.listGrantCandidates(resourceType, resourceId, keyword));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @PutMapping("/grants")
    public R<Boolean> upsertGrant(@RequestParam("resourceType") String resourceType,
                                   @RequestParam("resourceId") Long resourceId,
                                   @RequestBody GrantRequest request) {
        try {
            resourceVisibilityService.upsertGrant(resourceType, resourceId, request.accountId(),
                    Boolean.TRUE.equals(request.allowUse()));
            return R.data(true, "授权已保存");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @PutMapping("/grants/batch")
    public R<Integer> upsertGrants(@RequestParam("resourceType") String resourceType,
                                   @RequestParam("resourceId") Long resourceId,
                                   @RequestBody GrantBatchRequest request) {
        try {
            int count = resourceVisibilityService.upsertGrants(resourceType, resourceId,
                    request.accountIds(), request.departmentIds(), Boolean.TRUE.equals(request.allowUse()));
            return R.data(count, "授权已保存");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PermNeed({"system:admin", "agent:definition:edit", "tool:config:edit", "skill:package:edit",
            "knowledge:config:edit", "mcp:server:edit"})
    @DeleteMapping("/grants")
    public R<Boolean> revokeGrant(@RequestParam("resourceType") String resourceType,
                                   @RequestParam("resourceId") Long resourceId,
                                   @RequestParam(value = "accountId", required = false) Long accountId,
                                   @RequestParam(value = "departmentId", required = false) Long departmentId) {
        try {
            if ((accountId == null) == (departmentId == null)) {
                return R.fail("必须指定一个账号或部门");
            }
            if (accountId != null) {
                resourceVisibilityService.revokeGrant(resourceType, resourceId, accountId);
            } else {
                resourceVisibilityService.revokeDepartmentGrant(resourceType, resourceId, departmentId);
            }
            return R.data(true, "授权已撤销");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    public record GrantRequest(Long accountId, Boolean allowUse) {
    }

    public record GrantBatchRequest(List<Long> accountIds, List<Long> departmentIds, Boolean allowUse) {
    }

    /**
     * Fork 智能体：复制一份归当前账号所有的副本（默认仅创建者可见 + 停用）
     *
     * @param id   源智能体ID
     * @param name 副本名称，为空时按「源名称 副本」生成
     */
    @PermNeed({"agent:definition:fork"})
    @PostMapping("/fork/agent/{id}")
    public R<AgentDefinitionVO> forkAgent(@PathVariable("id") Long id,
                                          @RequestParam(value = "name", required = false) String name) {
        try {
            return R.data(agentDefinitionService.forkAgent(id, name), "已创建副本");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }
}
