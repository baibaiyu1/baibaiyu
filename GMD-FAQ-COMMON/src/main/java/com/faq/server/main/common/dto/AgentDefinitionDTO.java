package com.gmd.faq.server.main.common.dto;

import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.mp.annotation.QueryDefine;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.mp.support.QueryCondition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 智能体定义查询DTO
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefinitionDTO extends PageParams {

    @QueryDefine(value = "智能体名称", condition = QueryCondition.LIKE)
    private String name;

    @QueryDefine(value = "智能体类型", condition = QueryCondition.EQ)
    private AgentType agentType;

    @QueryDefine(value = "智能体代码", condition = QueryCondition.EQ)
    private String agentCode;


    private String tag;

    @QueryDefine(value = "是否可用", condition = QueryCondition.EQ)
    private Boolean enabled;

    /**
     * 可见性范围（不参与 SQL 自动拼接，由 Controller 手动追加过滤条件）
     * <p>
     * personal=我创建的，public=本组织公开，favorite=我收藏的，all/空=我可见的全部
     */
    private String scope;
    /** 当前资源空间，只收窄列表，不改变权限。 */
    private String space;
}
