package com.gmd.faq.server.main.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.gmd.faq.server.main.common.config.SerializableEnable;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 描述：AgentscopeSession
 *
 **/
@Getter
@Setter
@TableName(TableConst.AGENT_SCOPE_SESSIONS)
@AllArgsConstructor
@NoArgsConstructor
public class AgentScopeSession implements SerializableEnable {
    @TableId
    private String sessionId;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private String stateKey;
    private Integer itemIndex;
    private String stateData;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
