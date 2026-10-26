package com.gmd.faq.server.main.websocket.model;

import com.gmd.faq.server.main.common.enums.WsMessageType;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * 描述：WebSocket 消息包装类，用于客户端发送消息给服务器
 *
 **/
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WsClientMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private WsMessageType type;
    private Object content;
}
