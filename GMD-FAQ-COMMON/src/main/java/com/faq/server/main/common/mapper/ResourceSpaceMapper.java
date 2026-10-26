package com.gmd.faq.server.main.common.mapper;

import com.gmd.faq.server.main.common.consts.TableConst;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface ResourceSpaceMapper {
    @Select("SELECT id, parent_id, name, enabled FROM " + TableConst.DEPARTMENT + " ORDER BY sort_order, id")
    List<Map<String, Object>> departments();

    @Select("SELECT department_id FROM " + TableConst.DEPARTMENT_LEADER + " WHERE account_id = #{accountId}")
    List<Long> ledDepartments(@Param("accountId") Long accountId);

    @Select("SELECT dept_id FROM " + TableConst.ACCOUNT + " WHERE id = #{accountId} AND enabled = 1")
    Long accountDepartment(@Param("accountId") Long accountId);

    @Select("SELECT (SELECT COUNT(*) FROM " + TableConst.AGENT + " WHERE ownership_type = 'DEPARTMENT' AND owning_dept_id = #{id})"
            + " + (SELECT COUNT(*) FROM " + TableConst.TOOL + " WHERE ownership_type = 'DEPARTMENT' AND owning_dept_id = #{id})"
            + " + (SELECT COUNT(*) FROM " + TableConst.SKILL + " WHERE ownership_type = 'DEPARTMENT' AND owning_dept_id = #{id})"
            + " + (SELECT COUNT(*) FROM " + TableConst.KNOWLEDGE + " WHERE ownership_type = 'DEPARTMENT' AND owning_dept_id = #{id})"
            + " + (SELECT COUNT(*) FROM " + TableConst.MCP + " WHERE ownership_type = 'DEPARTMENT' AND owning_dept_id = #{id})")
    long countDepartmentAssets(@Param("id") Long id);
}
