package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 敏感词配置查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SensitiveWordConfigDTO extends PageParams {

    @QueryDefine(value = "分类", condition = QueryCondition.EQ)
    private String category;

    @QueryDefine(value = "配置名称", condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;
}
