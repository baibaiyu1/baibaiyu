package com.gmd.faq.server.main.console.heartbeat;

import com.gmd.faq.server.main.console.heartbeat.model.NodeStatusVO;
import com.gmd.faq.server.main.console.heartbeat.model.ServiceInfo;
import com.gmd.faq.server.main.heartbeat.HeartbeatPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：内存节点注册表
 * 维护所有执行节点及其服务的运行状态，基于 ConcurrentHashMap 实现线程安全存储
 *
 **/
@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    /** 服务超时阈值：超过 90s 未心跳标记为 DOWN */
    private static final Duration SERVICE_TIMEOUT = Duration.ofSeconds(90);

    /** 节点清除阈值：所有服务 DOWN 且超过 5min 无心跳则移除 */
    private static final Duration NODE_EVICTION = Duration.ofMinutes(5);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** 节点状态枚举 */
    private enum ServiceStatus { UP, DOWN }

    /** 节点注册表：nodeId -> NodeState */
    private final ConcurrentHashMap<String, NodeState> nodes = new ConcurrentHashMap<>();

    /**
     * 接收心跳上报，更新节点/服务状态
     */
    public void report(HeartbeatPayload payload) {
        String nodeId = payload.getNodeId();
        String serviceType = payload.getServiceType();
        Instant now = Instant.now();

        NodeState nodeState = nodes.computeIfAbsent(nodeId, k -> {
            NodeState ns = new NodeState();
            ns.nodeId = k;
            ns.hostname = payload.getHostname();
            ns.ip = payload.getIp();
            ns.firstSeenAt = now;
            ns.lastUpdatedAt = now;
            ns.services = new ConcurrentHashMap<>();
            log.info("新节点注册 - nodeId={}, hostname={}, ip={}", k, payload.getHostname(), payload.getIp());
            return ns;
        });

        // 更新节点信息（hostname/ip 可能随重启变化）
        nodeState.hostname = payload.getHostname();
        nodeState.ip = payload.getIp();
        nodeState.lastUpdatedAt = now;

        // 更新服务状态
        ServiceState serviceState = nodeState.services.computeIfAbsent(serviceType, k -> {
            ServiceState ss = new ServiceState();
            ss.serviceType = k;
            ss.startedAt = now;
            return ss;
        });

        // 若服务之前是 DOWN，重新 UP 时重置 startedAt（表示当前进程的新生命周期）
        if (serviceState.status == ServiceStatus.DOWN) {
            serviceState.startedAt = now;
        }

        serviceState.status = ServiceStatus.UP;
        serviceState.port = payload.getPort();
        serviceState.lastHeartbeat = now;

        log.debug("心跳已更新 - nodeId={}, serviceType={}", nodeId, serviceType);
    }

    /**
     * 获取所有节点状态快照（供前端查询）
     */
    public List<NodeStatusVO> getAllNodes() {
        // 先刷新一次状态（将超时的服务标记为 DOWN）
        refreshStatuses();

        List<NodeStatusVO> result = new ArrayList<>();
        for (NodeState ns : nodes.values()) {
            NodeStatusVO vo = new NodeStatusVO();
            vo.setNodeId(ns.nodeId);
            vo.setHostname(ns.hostname);
            vo.setIp(ns.ip);
            vo.setFirstSeenAt(FMT.format(ns.firstSeenAt));
            vo.setLastUpdatedAt(FMT.format(ns.lastUpdatedAt));

            List<ServiceInfo> serviceInfos = new ArrayList<>();
            int upCount = 0;
            for (ServiceState ss : ns.services.values()) {
                ServiceInfo info = new ServiceInfo();
                info.setServiceType(ss.serviceType);
                info.setStatus(ss.status.name());
                info.setPort(ss.port);
                info.setLastHeartbeat(ss.lastHeartbeat != null ? FMT.format(ss.lastHeartbeat) : null);
                info.setStartedAt(ss.startedAt != null ? FMT.format(ss.startedAt) : null);
                serviceInfos.add(info);
                if (ss.status == ServiceStatus.UP) {
                    upCount++;
                }
            }
            vo.setServices(serviceInfos);

            // 计算节点整体状态
            if (serviceInfos.isEmpty()) {
                vo.setNodeStatus("DOWN");
            } else if (upCount == serviceInfos.size()) {
                vo.setNodeStatus("HEALTHY");
            } else if (upCount == 0) {
                vo.setNodeStatus("DOWN");
            } else {
                vo.setNodeStatus("DEGRADED");
            }

            result.add(vo);
        }
        return result;
    }

    /**
     * 清理过期节点：
     * 1. 超过 90s 未心跳的服务 -> 标记 DOWN
     * 2. 所有服务均 DOWN 且 lastUpdatedAt 超过 5min -> 移除节点
     */
    public void cleanup() {
        Instant now = Instant.now();

        Iterator<Map.Entry<String, NodeState>> nodeIt = nodes.entrySet().iterator();
        while (nodeIt.hasNext()) {
            Map.Entry<String, NodeState> entry = nodeIt.next();
            NodeState ns = entry.getValue();

            // 刷新各服务状态
            for (ServiceState ss : ns.services.values()) {
                if (ss.status == ServiceStatus.UP && ss.lastHeartbeat != null
                        && Duration.between(ss.lastHeartbeat, now).compareTo(SERVICE_TIMEOUT) > 0) {
                    ss.status = ServiceStatus.DOWN;
                    log.info("服务标记 DOWN - nodeId={}, serviceType={}", ns.nodeId, ss.serviceType);
                }
            }

            // 判断是否需要移除节点
            boolean allDown = ns.services.values().stream().allMatch(ss -> ss.status == ServiceStatus.DOWN);
            if (allDown && Duration.between(ns.lastUpdatedAt, now).compareTo(NODE_EVICTION) > 0) {
                nodeIt.remove();
                log.info("节点已移除（僵尸清理） - nodeId={}", ns.nodeId);
            }
        }
    }

    /**
     * 刷新所有服务状态（将超时服务标记为 DOWN）
     */
    private void refreshStatuses() {
        Instant now = Instant.now();
        for (NodeState ns : nodes.values()) {
            for (ServiceState ss : ns.services.values()) {
                if (ss.status == ServiceStatus.UP && ss.lastHeartbeat != null
                        && Duration.between(ss.lastHeartbeat, now).compareTo(SERVICE_TIMEOUT) > 0) {
                    ss.status = ServiceStatus.DOWN;
                }
            }
        }
    }

    // ========== 内部数据结构 ==========

    private static class NodeState {
        String nodeId;
        String hostname;
        String ip;
        ConcurrentHashMap<String, ServiceState> services;
        Instant firstSeenAt;
        Instant lastUpdatedAt;
    }

    private static class ServiceState {
        String serviceType;
        Integer port;
        ServiceStatus status = ServiceStatus.DOWN;
        Instant lastHeartbeat;
        Instant startedAt;
    }
}
