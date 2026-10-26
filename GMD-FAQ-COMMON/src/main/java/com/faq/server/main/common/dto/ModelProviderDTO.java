package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型提供商查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelProviderDTO extends PageParams {

    @QueryDefine(value = "提供商名称", condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(value = "提供商类型", condition = QueryCondition.EQ)
    private String type;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;
}
