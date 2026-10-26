package com.gmd.faq.server.main.approval.mapper;

import com.gmd.faq.server.main.common.consts.TableConst;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 资源发布生效 Mapper
 * <p>
 * 审批通过后将资源置为「已发布 / 已启用」状态。
 * 表名统一使用 {@link TableConst} 常量拼接（编译期常量，可直接用于注解），
 * 租户条件由 MyBatis-Plus 租户拦截器自动注入。
 *
 */
@Mapper
public interface ResourcePublishMapper {

    /**
     * 智能体发布：置为启用
     *
     * @param id 智能体ID
     * @return 影响行数
     */
    @Update("UPDATE " + TableConst.AGENT + " SET published = 1, enabled = 1, updated_at = NOW() WHERE id = #{id}")
    int publishAgent(@Param("id") Long id);

    @Select("SELECT created_by FROM " + TableConst.WORKFLOW + " WHERE id = #{id}")
    List<Map<String, Object>> selectWorkflowCreator(@Param("id") Long id);

    /**
     * 工作流发布：置为已发布
     *
     * @param id 工作流ID
     * @return 影响行数
     */
    @Update("UPDATE " + TableConst.WORKFLOW + " SET status = 'PUBLISHED', updated_at = NOW() WHERE id = #{id}")
    int publishWorkflow(@Param("id") Long id);
}
