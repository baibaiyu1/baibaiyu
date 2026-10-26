package com.gmd.faq.server.main.console.heartbeat.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 描述：WebSocket 节点状态 VO
 * 用于前端展示 websocket 消息服务的运行状态
 *
 **/
@Setter
@Getter
public class WebSocketNodeVO {

    /** 节点唯一标识 */
    private String nodeId;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** 服务端口 */
    private Integer port;

    /** 节点状态：UP / DOWN */
    private String status;

    /** 首次注册时间 */
    private String firstSeenAt;

    /** 最近更新时间 */
    private String lastUpdatedAt;

    /** 服务启动时间 */
    private String startedAt;

    /** 最后心跳时间 */
    private String lastHeartbeat;
}
