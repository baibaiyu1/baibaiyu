package com.gmd.faq.server.main.common.entity;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 智能体对话Key
 *
 */
@Getter
@Setter
@TableName(TableConst.AGENT_CHAT_KEY)
@AllArgsConstructor
@NoArgsConstructor
public class AgentChatKey implements SerializableEnable {
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private String agentCode;
    private String chatKey;
}
