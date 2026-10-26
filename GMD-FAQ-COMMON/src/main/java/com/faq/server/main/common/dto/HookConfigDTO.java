package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.enums.HookType;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Hook配置查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HookConfigDTO extends PageParams {

    @QueryDefine(value = "Hook名称", condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(value = "Hook类型", condition = QueryCondition.EQ)
    private HookType hookType;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;
}
