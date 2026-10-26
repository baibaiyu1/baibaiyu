package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(TableConst.AGENT_TAG)
public class AgentTag extends BaseTenantEntity {
    private String name;
}
