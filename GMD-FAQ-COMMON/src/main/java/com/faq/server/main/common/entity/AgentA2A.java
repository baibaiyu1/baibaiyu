package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.config.mybatis.JsonNodeTypeHandler;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.A2aType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 描述：Agent和A2A的关联关系
 *
 **/
@Getter
@Setter
@NoArgsConstructor
@TableName(value = TableConst.AGENT_A2A, autoResultMap = true)
public class AgentA2A implements SerializableEnable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long agentDefinitionId;
    private A2aType a2aType;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode a2aConfig;

    public AgentA2A(Long agentDefinitionId, A2aType a2aType, JsonNode a2aConfig) {
        this.agentDefinitionId = agentDefinitionId;
        this.a2aType = a2aType;
        this.a2aConfig = a2aConfig;
    }
}
