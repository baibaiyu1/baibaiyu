package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 智能体与知识库关联
 *
 */
@Getter
@Setter
@TableName(TableConst.AGENT_KNOWLEDGE)
@AllArgsConstructor
public class AgentKnowledgeBase implements SerializableEnable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long agentDefinitionId;
    private Long knowledgeBaseConfigId;
}
