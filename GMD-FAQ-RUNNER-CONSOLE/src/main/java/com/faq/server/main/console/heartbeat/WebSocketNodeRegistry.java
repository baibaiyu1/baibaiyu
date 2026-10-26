package com.gmd.faq.server.main.console.heartbeat;

import com.gmd.faq.server.main.console.heartbeat.model.WebSocketNodeVO;
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
 * 描述：WebSocket 节点注册表
 * 独立于 NodeRegistry，维护所有 websocket 节点的运行状态
 * 支持 websocket 多节点部署场景
 *
 **/
@Component
public class WebSocketNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNodeRegistry.class);

    /** 服务超时阈值：超过 90s 未心跳标记为 DOWN */
    private static final Duration SERVICE_TIMEOUT = Duration.ofSeconds(90);

    /** 节点清除阈值：DOWN 状态超过 5min 则移除 */
    private static final Duration NODE_EVICTION = Duration.ofMinutes(5);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /** websocket 节点注册表：nodeId -> WebSocketNodeState */
    private final ConcurrentHashMap<String, WebSocketNodeState> nodes = new ConcurrentHashMap<>();

    /**
     * 接收 websocket 心跳上报，更新节点状态
     */
    public void report(HeartbeatPayload payload) {
        String nodeId = payload.getNodeId();
        Instant now = Instant.now();

        WebSocketNodeState state = nodes.computeIfAbsent(nodeId, k -> {
            WebSocketNodeState ns = new WebSocketNodeState();
            ns.nodeId = k;
            ns.hostname = payload.getHostname();
            ns.ip = payload.getIp();
            ns.firstSeenAt = now;
            ns.lastUpdatedAt = now;
            ns.startedAt = now;
            log.info("WebSocket 新节点注册 - nodeId={}, hostname={}, ip={}", k, payload.getHostname(), payload.getIp());
            return ns;
        });

        // 更新节点信息
        state.hostname = payload.getHostname();
        state.ip = payload.getIp();
        state.lastUpdatedAt = now;

        // 若之前是 DOWN，重新 UP 时重置 startedAt
        if (state.status == NodeStatus.DOWN) {
            state.startedAt = now;
        }

        state.status = NodeStatus.UP;
        state.lastHeartbeat = now;
        state.port = payload.getPort();

        log.debug("WebSocket 心跳已更新 - nodeId={}", nodeId);
    }

    /**
     * 获取所有 websocket 节点状态快照
     */
    public List<WebSocketNodeVO> getAllNodes() {
        refreshStatuses();
        List<WebSocketNodeVO> result = new ArrayList<>();

        for (WebSocketNodeState ns : nodes.values()) {
            WebSocketNodeVO vo = new WebSocketNodeVO();
            vo.setNodeId(ns.nodeId);
            vo.setHostname(ns.hostname);
            vo.setIp(ns.ip);
            vo.setPort(ns.port);
            vo.setStatus(ns.status.name());
            vo.setFirstSeenAt(ns.firstSeenAt != null ? FMT.format(ns.firstSeenAt) : null);
            vo.setLastUpdatedAt(ns.lastUpdatedAt != null ? FMT.format(ns.lastUpdatedAt) : null);
            vo.setStartedAt(ns.startedAt != null ? FMT.format(ns.startedAt) : null);
            vo.setLastHeartbeat(ns.lastHeartbeat != null ? FMT.format(ns.lastHeartbeat) : null);
            result.add(vo);
        }
        return result;
    }

    /**
     * 清理过期节点
     */
    public void cleanup() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, WebSocketNodeState>> nodeIt = nodes.entrySet().iterator();

        while (nodeIt.hasNext()) {
            Map.Entry<String, WebSocketNodeState> entry = nodeIt.next();
            WebSocketNodeState ns = entry.getValue();

            // 超时标记 DOWN
            if (ns.status == NodeStatus.UP && ns.lastHeartbeat != null
                    && Duration.between(ns.lastHeartbeat, now).compareTo(SERVICE_TIMEOUT) > 0) {
                ns.status = NodeStatus.DOWN;
                log.info("WebSocket 节点标记 DOWN - nodeId={}", ns.nodeId);
            }

            // 僵尸清理
            if (ns.status == NodeStatus.DOWN
                    && Duration.between(ns.lastUpdatedAt, now).compareTo(NODE_EVICTION) > 0) {
                nodeIt.remove();
                log.info("WebSocket 节点已移除（僵尸清理） - nodeId={}", ns.nodeId);
            }
        }
    }

    /**
     * 刷新所有节点状态
     */
    private void refreshStatuses() {
        Instant now = Instant.now();
        for (WebSocketNodeState ns : nodes.values()) {
            if (ns.status == NodeStatus.UP && ns.lastHeartbeat != null
                    && Duration.between(ns.lastHeartbeat, now).compareTo(SERVICE_TIMEOUT) > 0) {
                ns.status = NodeStatus.DOWN;
            }
        }
    }

    // ========== 内部数据结构 ==========

    private enum NodeStatus { UP, DOWN }

    private static class WebSocketNodeState {
        String nodeId;
        String hostname;
        String ip;
        Integer port;
        NodeStatus status = NodeStatus.DOWN;
        Instant firstSeenAt;
        Instant lastUpdatedAt;
        Instant startedAt;
        Instant lastHeartbeat;
    }
}
