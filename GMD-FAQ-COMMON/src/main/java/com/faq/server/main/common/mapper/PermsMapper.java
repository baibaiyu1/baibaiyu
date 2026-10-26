package com.gmd.faq.server.main.common.mapper;

import com.gmd.faq.server.main.common.consts.TableConst;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限点查询 Mapper（单轨权限）
 * <p>
 * 权限点 = 账号在当前组织下的「角色 → 菜单/按钮 perms」并集：
 * {@code role_account → role(启用) → role_menu → menu(启用)}。
 * 由 {@code AuthInterceptor} 在每个请求上解析一次并挂到 {@code UserDetail.perms}，
 * 后续任何位置都可直接用 {@code UserUtils.hasPerm(...)} 判定，不再查库。
 * <p>
 * <b>部门级角色过滤</b>：{@code gmd_faq_role.dept_id} 为 null 表示平台级角色（全平台可用），
 * 非空表示该部门（组织）的专属角色；账号能拿到的角色 = 平台级角色 +
 * 「自己所属部门及其全部祖先部门」的角色（{@code deptIds}）。
 * 取祖先链是为了支持「上级组织管理员管理下级组织」，但**不会**向下传递——
 * 组织自己的角色不会让上级组织凭空获得权限，也不会让下级组织越权拿到上级的角色。
 * <p>
 * 说明：{@code gmd_faq_menu} 是全局表（无 tenant_id，已在 {@code MybatisPlusConfig.IGNORE_TABLES} 白名单里），
 * 其余表由 MyBatis-Plus 租户拦截器自动补 {@code tenant_id} 条件，与显式传入的 tenantId 一致。
 *
 */
@Mapper
public interface PermsMapper {

    /**
     * 查询账号在指定租户下的全部权限点（去重）
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @param deptIds   账号所属部门（组织）及其祖先部门ID；为空表示只取平台级角色
     * @return 权限点列表（可能为空）
     */
    @Select("<script>"
            + "SELECT DISTINCT m.perms FROM " + TableConst.ROLE_ACCOUNT + " ra"
            + " JOIN " + TableConst.ROLE + " r ON r.id = ra.role_id AND r.enabled = 1"
            + " JOIN " + TableConst.ROLE_MENU + " rm ON rm.role_id = r.id"
            + " JOIN " + TableConst.MENU + " m ON m.id = rm.menu_id AND m.status = 1"
            + " WHERE ra.account_id = #{accountId} AND ra.tenant_id = #{tenantId}"
            + " <choose>"
            + "   <when test='deptIds != null and deptIds.size() > 0'>"
            + "     AND (r.dept_id IS NULL OR r.dept_id IN"
            + "       <foreach collection='deptIds' item='deptId' open='(' separator=',' close=')'>#{deptId}</foreach>)"
            + "   </when>"
            + "   <otherwise>AND r.dept_id IS NULL</otherwise>"
            + " </choose>"
            + " AND m.perms IS NOT NULL AND m.perms &lt;&gt; ''"
            + "</script>")
    List<String> selectPerms(@Param("accountId") Long accountId,
                             @Param("tenantId") Long tenantId,
                             @Param("deptIds") List<Long> deptIds);

    /**
     * 查询账号在指定租户下的角色编码列表（用于展示与排障，不参与鉴权）
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @param deptIds   账号所属部门（组织）及其祖先部门ID；为空表示只取平台级角色
     * @return 角色编码列表
     */
    @Select("<script>"
            + "SELECT DISTINCT r.code FROM " + TableConst.ROLE_ACCOUNT + " ra"
            + " JOIN " + TableConst.ROLE + " r ON r.id = ra.role_id AND r.enabled = 1"
            + " WHERE ra.account_id = #{accountId} AND ra.tenant_id = #{tenantId}"
            + " <choose>"
            + "   <when test='deptIds != null and deptIds.size() > 0'>"
            + "     AND (r.dept_id IS NULL OR r.dept_id IN"
            + "       <foreach collection='deptIds' item='deptId' open='(' separator=',' close=')'>#{deptId}</foreach>)"
            + "   </when>"
            + "   <otherwise>AND r.dept_id IS NULL</otherwise>"
            + " </choose>"
            + "</script>")
    List<String> selectRoleCodes(@Param("accountId") Long accountId,
                                 @Param("tenantId") Long tenantId,
                                 @Param("deptIds") List<Long> deptIds);

    /**
     * 查询账号在指定租户下所有角色的数据权限范围（多角色在 Service 侧取最宽）
     *
     * @param accountId 账号ID
     * @param tenantId  租户ID
     * @param deptIds   账号所属部门（组织）及其祖先部门ID；为空表示只取平台级角色
     * @return data_scope 取值列表
     */
    @Select("<script>"
            + "SELECT r.data_scope FROM " + TableConst.ROLE_ACCOUNT + " ra"
            + " JOIN " + TableConst.ROLE + " r ON r.id = ra.role_id AND r.enabled = 1"
            + " WHERE ra.account_id = #{accountId} AND ra.tenant_id = #{tenantId}"
            + " <choose>"
            + "   <when test='deptIds != null and deptIds.size() > 0'>"
            + "     AND (r.dept_id IS NULL OR r.dept_id IN"
            + "       <foreach collection='deptIds' item='deptId' open='(' separator=',' close=')'>#{deptId}</foreach>)"
            + "   </when>"
            + "   <otherwise>AND r.dept_id IS NULL</otherwise>"
            + " </choose>"
            + "</script>")
    List<String> selectDataScopes(@Param("accountId") Long accountId,
                                  @Param("tenantId") Long tenantId,
                                  @Param("deptIds") List<Long> deptIds);

    /**
     * 查询账号所属部门（组织）ID
     * <p>
     * {@code gmd_faq_account} 无 tenant_id（已在 {@code MybatisPlusConfig.IGNORE_TABLES} 白名单里），
     * 且「部门即组织」后一个账号只归属于一个部门，故不加租户条件。
     *
     * @param accountId 账号ID
     * @return 部门ID，未设置返回 null
     */
    @Select("SELECT dept_id FROM " + TableConst.ACCOUNT + " WHERE id = #{accountId}")
    Long selectDeptId(@Param("accountId") Long accountId);

    /**
     * 查询全部部门（组织）的 id 与父 id，用于在 Java 侧构建祖先链
     * <p>
     * 刻意不用递归 CTE：MyBatis-Plus 租户拦截器会给 SQL 里每一张「表」（含 CTE 别名）
     * 注入 tenant_id，递归 CTE 会直接报 {@code Unknown column 'x.tenant_id'}；
     * 部门数据量很小（每组织通常个位数到几十行），一次查询 + 内存上溯足够。
     * <p>
     * 返回值形如 {@code [{"id":1,"parent_id":0}, ...]}，由 MyBatis 映射成 Map。
     *
     * @return 部门 id / parent_id 列表
     */
    @Select("SELECT id, parent_id FROM " + TableConst.DEPARTMENT)
    List<java.util.Map<String, Object>> selectDeptIdAndParent();
}

