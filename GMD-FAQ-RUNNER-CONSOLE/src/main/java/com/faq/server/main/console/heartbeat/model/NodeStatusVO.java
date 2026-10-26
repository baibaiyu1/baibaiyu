package com.gmd.faq.server.main.console.heartbeat.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 描述：执行节点状态 VO
 * 聚合节点基本信息及其所有服务的运行状态，用于前端展示
 *
 **/
@Setter
@Getter
public class NodeStatusVO {

    /** 节点唯一标识 */
    private String nodeId;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** 节点首次注册时间 */
    private String firstSeenAt;

    /** 节点最近一次任意服务心跳时间 */
    private String lastUpdatedAt;

    /** 节点内各服务状态列表 */
    private List<ServiceInfo> services;

    /**
     * 节点整体状态：
     * HEALTHY  - 所有服务均 UP
     * DEGRADED - 部分服务 UP、部分 DOWN
     * DOWN     - 所有服务均 DOWN
     */
    private String nodeStatus;

}
