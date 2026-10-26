package com.gmd.faq.server.main.common.config.auth;

import com.gmd.faq.server.main.common.enums.DataScope;
import com.gmd.faq.server.main.common.mapper.PermsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 权限点解析器（单轨权限）
 * <p>
 * 负责把「账号 + 租户 + 部门（组织）」解析成权限点集合。解析入口只在
 * {@link AuthInterceptor}（每个请求一次）与调度器等无请求上下文的入口使用，
 * 业务代码一律用 {@code UserUtils.hasPerm(...)} 读 {@code UserDetail.perms}，避免重复查库。
 * <p>
 * 「部门即组织」后，角色多了「部门级」这一维度（{@code role.dept_id}）：
 * 账号能拿到的角色 = 平台级角色 + 「自己所属部门及其全部祖先部门」的角色。
 * 之所以要带上祖先：部门树里「顶级组织 → 下级组织」是层级关系，
 * 上级组织的管理员需要能管理下级组织；反过来下级组织拿不到上级组织的角色，不会越权。
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermsResolver {

    private final PermsMapper permsMapper;

    /**
     * 解析权限点（不限部门级角色，仅用于无部门上下文的场景）
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @return 权限点列表；参数缺失或查询异常时返回空列表（宁可少权限，不可多权限）
     */
    public List<String> resolve(Long accountId, Long tenantId) {
        return resolve(accountId, tenantId, null);
    }

    /**
     * 解析权限点
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @param deptId    账号所属部门（组织）ID，可为空（为空时只取平台级角色）
     * @return 权限点列表；参数缺失或查询异常时返回空列表（宁可少权限，不可多权限）
     */
    public List<String> resolve(Long accountId, Long tenantId, Long deptId) {
        if (accountId == null || tenantId == null) {
            return Collections.emptyList();
        }
        try {
            List<String> perms = permsMapper.selectPerms(accountId, tenantId, deptLineage(deptId));
            return perms == null ? Collections.emptyList() : perms;
        } catch (Exception e) {
            log.warn("解析权限点失败，accountId={} tenantId={} deptId={}", accountId, tenantId, deptId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析角色编码（仅用于展示/排障）
     */
    public List<String> resolveRoleCodes(Long accountId, Long tenantId, Long deptId) {
        if (accountId == null || tenantId == null) {
            return Collections.emptyList();
        }
        List<String> codes = permsMapper.selectRoleCodes(accountId, tenantId, deptLineage(deptId));
        return codes == null ? Collections.emptyList() : codes;
    }

    /**
     * 解析当前请求的完整访问上下文（权限点 + 数据范围 + 部门）
     * <p>
     * 供 {@link AuthInterceptor} 每请求调用一次；任何一步失败都退化为「最小权限」而不是放大权限。
     * 顺序很关键：先取 dept_id（部门级角色的过滤依据），再解析权限点与数据范围。
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @return 访问上下文
     */
    public AccessContext resolveAccess(Long accountId, Long tenantId) {
        if (accountId == null || tenantId == null) {
            return new AccessContext(Collections.emptyList(), DataScope.SELF, null);
        }

        Long deptId = null;
        try {
            deptId = permsMapper.selectDeptId(accountId);
        } catch (Exception e) {
            log.warn("解析账号部门失败，accountId={}", accountId, e);
        }

        List<Long> lineage = deptLineage(deptId);

        List<String> perms;
        try {
            perms = permsMapper.selectPerms(accountId, tenantId, lineage);
            if (perms == null) {
                perms = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("解析权限点失败，accountId={} tenantId={} deptId={}", accountId, tenantId, deptId, e);
            perms = Collections.emptyList();
        }

        DataScope dataScope = DataScope.SELF;
        try {
            dataScope = DataScope.widest(permsMapper.selectDataScopes(accountId, tenantId, lineage));
        } catch (Exception e) {
            log.warn("解析数据权限范围失败，按仅本人处理，accountId={} tenantId={}", accountId, tenantId, e);
        }

        return new AccessContext(perms, dataScope, deptId);
    }

    /**
     * 构建「账号所属部门 + 其全部祖先部门」的ID链
     * <p>
     * 最多上溯 {@value #MAX_DEPT_DEPTH} 层，防止历史脏数据形成的环导致死循环。
     *
     * @param deptId 账号所属部门ID，可为空
     * @return 部门ID链；deptId 为空时返回空列表（只取平台级角色）
     */
    private List<Long> deptLineage(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        List<Long> lineage = new java.util.ArrayList<>();
        try {
            List<java.util.Map<String, Object>> rows = permsMapper.selectDeptIdAndParent();
            java.util.Map<Long, Long> parentOf = new java.util.HashMap<>();
            for (java.util.Map<String, Object> row : rows) {
                Long id = toLong(row.get("id"));
                if (id != null) {
                    parentOf.put(id, toLong(row.get("parent_id")));
                }
            }
            Long current = deptId;
            int depth = 0;
            while (current != null && current != 0L && depth++ < MAX_DEPT_DEPTH && !lineage.contains(current)) {
                lineage.add(current);
                current = parentOf.get(current);
            }
        } catch (Exception e) {
            log.warn("解析部门祖先链失败，退化为仅本部门，deptId={}", deptId, e);
            return List.of(deptId);
        }
        return lineage.isEmpty() ? List.of(deptId) : lineage;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 部门树上溯的最大层数（防御历史脏数据成环） */
    private static final int MAX_DEPT_DEPTH = 32;

    /**
     * 访问上下文：权限点 + 数据权限范围 + 部门
     *
     * @param perms     权限点集合
     * @param dataScope 数据权限范围（多角色取最宽）
     * @param deptId    所属部门ID
     */
    public record AccessContext(List<String> perms, DataScope dataScope, Long deptId) {
    }
}
