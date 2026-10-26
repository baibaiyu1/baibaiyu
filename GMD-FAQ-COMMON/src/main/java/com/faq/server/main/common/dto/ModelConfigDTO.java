package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型配置查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelConfigDTO extends PageParams {

    @QueryDefine(value = "提供商ID", condition = QueryCondition.EQ)
    private Long providerId;

    @QueryDefine(value = "模型名称", condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;
}
