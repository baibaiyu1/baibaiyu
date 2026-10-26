package com.gmd.faq.server.main.console.heartbeat;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 描述：节点状态定时清理任务
 * 每 30s 执行一次，清除超时未心跳的僵尸节点
 *
 **/
@Component
@RequiredArgsConstructor
public class NodeCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(NodeCleanupTask.class);

    private final NodeRegistry nodeRegistry;

    private final WebSocketNodeRegistry webSocketNodeRegistry;

    @Scheduled(fixedRate = 30000)
    public void cleanup() {
        try {
            nodeRegistry.cleanup();
        } catch (Exception e) {
            log.error("节点清理任务执行异常", e);
        }
        try {
            webSocketNodeRegistry.cleanup();
        } catch (Exception e) {
            log.error("WebSocket 节点清理任务执行异常", e);
        }
    }
}
