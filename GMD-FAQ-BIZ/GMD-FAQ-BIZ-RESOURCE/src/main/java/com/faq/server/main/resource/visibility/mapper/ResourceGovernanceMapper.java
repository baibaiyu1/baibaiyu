package com.gmd.faq.server.main.resource.visibility.mapper;

import com.gmd.faq.server.main.common.consts.TableConst;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 资源可见性变更 Mapper
 * <p>
 * 表名统一使用 {@link TableConst} 常量拼接（编译期常量，可直接用于注解），
 * 租户条件由 MyBatis-Plus 租户拦截器自动注入。
 * <p>
 * 维护及移交资格由 Service 按资产归属校验；可见性与移交操作先锁定当前归属。
 *
 */
@Mapper
public interface ResourceGovernanceMapper {

    /** 表名只能来自 Service 的 TableConst 白名单；租户隔离由拦截器追加。 */
    @Update("UPDATE ${table} SET visibility = #{visibility}, updated_at = NOW(), updated_by = #{accountId} WHERE id = #{id}")
    int updateVisibility(@Param("table") String table, @Param("id") Long id,
                         @Param("visibility") String visibility, @Param("accountId") Long accountId);

    @Select("SELECT owner_id, visibility, resource_dept_id, ownership_type, owning_dept_id, maintainer_id FROM ${table} WHERE id = #{id} FOR UPDATE")
    List<Map<String, Object>> lockOwnership(@Param("table") String table, @Param("id") Long id);

    @Update("UPDATE ${table} SET ownership_type = #{ownershipType}, owner_id = #{ownerId},"
            + " owning_dept_id = #{departmentId}, maintainer_id = #{maintainerId}, updated_at = NOW(),"
            + " updated_by = #{accountId} WHERE id = #{id}")
    int transferOwnership(@Param("table") String table, @Param("id") Long id,
                          @Param("ownershipType") String ownershipType, @Param("ownerId") Long ownerId,
                          @Param("departmentId") Long departmentId, @Param("maintainerId") Long maintainerId,
                          @Param("accountId") Long accountId);

    /**
     * 批量读取资源可见性与归属人
     * <p>
     * 前端卡片展示归属和操作资格时，按 ID 批量补读，由 Service 统一计算权限。
     * <p>
     * {@code table} 只允许传入 {@link TableConst} 常量（由 Service 按资源类型 switch 后传入），
     * 不接受外部输入，故使用 {@code ${}} 拼接表名是安全的。
     *
     * @param table 资源表名（TableConst 常量）
     * @param ids   资源ID集合
     * @return 每行包含 id、visibility 与 owner_id
     */
    @Select("<script>SELECT id, visibility, owner_id, resource_dept_id, ownership_type, owning_dept_id, maintainer_id FROM ${table} WHERE id IN "
            + "<foreach collection='ids' item='item' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectVisibility(@Param("table") String table, @Param("ids") Collection<Long> ids);

    @Select("<script>SELECT id, visibility, owner_id, resource_dept_id, ownership_type, owning_dept_id, maintainer_id, enabled, published FROM "
            + TableConst.AGENT + " WHERE id IN "
            + "<foreach collection='ids' item='item' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectAgentVisibility(@Param("ids") Collection<Long> ids);

    /**
     * 读取资源归属人（供资源级判权使用）
     * <p>
     * 返回空列表表示资源不存在；归属类型须读取 ownership_type，不能由空 owner_id 推断。
     *
     * @param table 资源表名（TableConst 常量）
     * @param id    资源ID
     * @return 单行归属与可见性结果
     */
    @Select("SELECT owner_id, visibility, resource_dept_id, ownership_type, owning_dept_id, maintainer_id FROM ${table} WHERE id = #{id}")
    List<Map<String, Object>> selectOwnerId(@Param("table") String table, @Param("id") Long id);

    @Select("SELECT owner_id, visibility, resource_dept_id, ownership_type, owning_dept_id, maintainer_id, enabled, published FROM "
            + TableConst.AGENT + " WHERE id = #{id}")
    List<Map<String, Object>> selectAgentAccessRow(@Param("id") Long id);

    @Select("SELECT department_id FROM " + TableConst.DEPARTMENT_LEADER + " WHERE account_id = #{accountId}")
    List<Long> selectLedDepartmentIds(@Param("accountId") Long accountId);

    @Select("SELECT dept_id FROM " + TableConst.ACCOUNT + " WHERE id = #{accountId}")
    Long selectAccountDeptId(@Param("accountId") Long accountId);

    @Select("SELECT COUNT(*) FROM " + TableConst.ACCOUNT + " a WHERE a.id = #{accountId} AND a.enabled = 1 AND ("
            + "EXISTS (SELECT 1 FROM " + TableConst.DEPARTMENT + " d WHERE d.id = a.dept_id)"
            + " OR a.last_tenant_id = #{tenantId}"
            + " OR EXISTS (SELECT 1 FROM " + TableConst.ACCOUNT_TENANT
            + " at WHERE at.account_id = a.id AND at.tenant_id = #{tenantId})"
            + " OR EXISTS (SELECT 1 FROM " + TableConst.ROLE_ACCOUNT
            + " ra WHERE ra.account_id = a.id AND ra.tenant_id = #{tenantId}))")
    long countAccountInTenant(@Param("accountId") Long accountId, @Param("tenantId") Long tenantId);

    @Select("<script>SELECT DISTINCT a.id, a.nickname, a.username, d.name AS deptName FROM "
            + TableConst.ACCOUNT + " a LEFT JOIN " + TableConst.DEPARTMENT
            + " d ON d.id = a.dept_id WHERE a.enabled = 1 AND (d.id IS NOT NULL"
            + " OR a.last_tenant_id = #{tenantId}"
            + " OR EXISTS (SELECT 1 FROM " + TableConst.ACCOUNT_TENANT
            + " at WHERE at.account_id = a.id AND at.tenant_id = #{tenantId})"
            + " OR EXISTS (SELECT 1 FROM " + TableConst.ROLE_ACCOUNT
            + " ra WHERE ra.account_id = a.id AND ra.tenant_id = #{tenantId}))"
            + " <if test='keyword != null and keyword != &quot;&quot;'>"
            + " AND (a.username LIKE CONCAT('%', #{keyword}, '%') OR a.nickname LIKE CONCAT('%', #{keyword}, '%'))"
            + " </if> ORDER BY a.username LIMIT 50</script>")
    List<Map<String, Object>> selectGrantCandidates(@Param("tenantId") Long tenantId,
                                                      @Param("keyword") String keyword);

    @Select("SELECT g.account_id, g.allow_use, a.nickname, a.username FROM "
            + TableConst.RESOURCE_ACCESS_GRANT + " g LEFT JOIN " + TableConst.ACCOUNT
            + " a ON a.id = g.account_id WHERE g.resource_type = #{resourceType} AND g.resource_id = #{resourceId}"
            + " ORDER BY a.username")
    List<Map<String, Object>> selectGrantRows(@Param("resourceType") String resourceType,
                                               @Param("resourceId") Long resourceId);

    @Select("SELECT g.department_id, g.allow_use, d.name AS departmentName FROM "
            + TableConst.RESOURCE_DEPARTMENT_GRANT + " g JOIN " + TableConst.DEPARTMENT
            + " d ON d.id = g.department_id WHERE g.resource_type = #{resourceType}"
            + " AND g.resource_id = #{resourceId} ORDER BY d.name")
    List<Map<String, Object>> selectDepartmentGrantRows(@Param("resourceType") String resourceType,
                                                         @Param("resourceId") Long resourceId);

    /**
     * 查询本租户全部部门（仅 id 与 parent_id），供 Service 在内存里组装部门子树
     * <p>
     * **不要**在这里写递归 CTE：MyBatis-Plus 租户拦截器会给 SQL 里**每一张「表」**注入 tenant_id 条件，
     * 连 CTE 的别名也会被当成表（实测报 {@code Unknown column 't.tenant_id' in 'on clause'}）。
     * 部门数据量很小，取回后在 Java 侧 BFS 更稳。
     *
     * @return 每行包含 id 与 parent_id
     */
    @Select("SELECT id, parent_id FROM " + TableConst.DEPARTMENT)
    List<Map<String, Object>> selectDeptIdAndParent();

    /**
     * 查询指定部门下的账号ID
     * <p>
     * {@code gmd_faq_account} 无 tenant_id（租户关系在 account_tenant），故不加租户条件；
     * 部门本身已按租户隔离。
     *
     * @param deptIds 部门ID集合
     * @return 账号ID列表
     */
    @Select("<script>SELECT id FROM " + TableConst.ACCOUNT + " WHERE dept_id IN "
            + "<foreach collection='deptIds' item='item' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</script>")
    List<Long> selectAccountIdsByDeptIds(@Param("deptIds") Collection<Long> deptIds);
}
