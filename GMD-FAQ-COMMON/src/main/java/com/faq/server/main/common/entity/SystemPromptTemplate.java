package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统提示词模板
 *
 */
@Getter
@Setter
@TableName(TableConst.PROMPT)
public class SystemPromptTemplate extends BaseTenantEntity {

    /**
     * 分类
     */
    private String category;

    /**
     * 模板名称
     */
    private String name;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 模板内容
     */
    private String content;

    /**
     * 使用次数统计
     */
    private Integer usageCount;
}
