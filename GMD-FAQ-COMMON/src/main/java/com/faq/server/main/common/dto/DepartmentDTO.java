package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门查询 DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DepartmentDTO extends PageParams {

    /**
     * 部门名称（模糊匹配）
     */
    @QueryDefine(condition = QueryCondition.LIKE)
    private String name;

    /**
     * 父部门ID（精确匹配）
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private Long parentId;

    /**
     * 是否可用
     */
    @QueryDefine(condition = QueryCondition.EQ)
    private Boolean enabled;

    /**
     * 用户列表左侧部门树筛选时使用的部门ID（含子部门）
     */
    private Long scopeDeptId;
}
