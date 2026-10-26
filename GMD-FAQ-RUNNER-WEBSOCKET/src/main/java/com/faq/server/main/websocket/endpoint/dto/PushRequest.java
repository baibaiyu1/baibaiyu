package com.gmd.faq.server.main.websocket.endpoint.dto;

import com.gmd.faq.server.main.websocket.model.WsServerMessage;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 描述：WebSocket 推送请求 DTO - 统一承载各类推送参数
 *
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PushRequest {

    /**
     * 目标客户端 ID（单客户端推送时必填）
     */
    private String clientId;

    /**
     * 目标用户 ID（按用户批量推送时必填）
     */
    private String userId;

    /**
     * 目标用户编码（按用户编码推送时必填）
     */
    private String userCode;

    /**
     * 租户 ID（跨进程传递，用于多租户隔离）
     */
    private Long tenantId;

    /**
     * 消息类型
     */
    private String type;

    /**
     * 消息内容（任意 JSON 可序列化对象）
     */
    private Object content;

    /**
     * 构建 WsServerMessage
     */
    public WsServerMessage toWsMessage() {
        return WsServerMessage.build(this.type, this.content);
    }
}
