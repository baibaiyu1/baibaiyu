package com.gmd.faq.server.main.common.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 部门层级计算（纯内存，无数据库依赖）
 * <p>
 * 调用方先查出本租户全部部门的 {@code id / parent_id}（一次查询即可，部门数据量很小），
 * 再由本类在 Java 侧做上下行遍历。
 * <p>
 * <b>不要改成递归 CTE</b>：MyBatis-Plus 租户拦截器会给 SQL 里每一张「表」注入 {@code tenant_id}，
 * 连 CTE 别名也会被当成表（实测报 {@code Unknown column 't.tenant_id' in 'on clause'}）。
 *
 */
public final class ResourceDeptSupport {

    private ResourceDeptSupport() {
    }

    /**
     * 把 {@code id / parent_id} 行集合转成「子 → 父」映射
     *
     * @param rows 每行包含 id 与 parent_id
     * @return 子部门ID → 父部门ID
     */
    public static Map<Long, Long> parentMap(List<Map<String, Object>> rows) {
        Map<Long, Long> parentOf = new HashMap<>();
        if (rows == null) {
            return parentOf;
        }
        for (Map<String, Object> row : rows) {
            Long id = toLong(row.get("id"));
            if (id != null) {
                parentOf.put(id, toLong(row.get("parent_id")));
            }
        }
        return parentOf;
    }

    /**
     * 自某部门向下收集「本部门 + 全部下级部门」
     *
     * @param parentOf 子 → 父映射
     * @param deptId   起始部门ID
     * @return 部门ID列表（含自身）；入参为空时返回空列表
     */
    public static List<Long> collectSubtree(Map<Long, Long> parentOf, Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (Map.Entry<Long, Long> entry : parentOf.entrySet()) {
            Long parent = entry.getValue() == null ? 0L : entry.getValue();
            childrenOf.computeIfAbsent(parent, key -> new ArrayList<>()).add(entry.getKey());
        }
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(deptId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            result.add(current);
            queue.addAll(childrenOf.getOrDefault(current, List.of()));
        }
        return result;
    }

    /**
     * 自某部门向上收集「本部门 + 全部上级部门」
     * <p>
     * 用于查找与当前部门同一祖先链上的公开资源；兄弟部门不在此链上。
     *
     * @param parentOf 子 → 父映射
     * @param deptId   起始部门ID
     * @return 部门ID列表（含自身，自身在最前）
     */
    public static List<Long> collectAncestors(Map<Long, Long> parentOf, Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        Long current = deptId;
        while (current != null && current != 0L && result.add(current)) {
            current = parentOf.get(current);
        }
        return new ArrayList<>(result);
    }

    /**
     * 宽松地把查询结果转为 Long（MySQL 的 bigint 可能以 BigInteger/Long/Integer 返回）
     *
     * @param value 原始值
     * @return 转换结果，无法转换时返回 null
     */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
