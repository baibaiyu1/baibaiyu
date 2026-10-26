package com.gmd.faq.server.main.websocket.heartbeat;

import com.gmd.faq.server.main.heartbeat.HeartbeatPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 描述：WebSocket 服务心跳定时发送器
 * 独立于现有 HeartbeatSender，发送到 /heartbeat/websocket/report 端点
 * 与 NodeRegistry 的心跳逻辑完全隔离，互不干扰
 *
 **/
@Component
public class WebSocketHeartbeatSender {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHeartbeatSender.class);

    /** 是否启用心跳上报 */
    @Value("${heartbeat.enabled:false}")
    private boolean enabled;

    /** console 服务基础 URL */
    @Value("${heartbeat.console-url:http://localhost:3060}")
    private String consoleUrl;

    /** 心跳发送间隔（秒） */
    @Value("${heartbeat.interval-seconds:30}")
    private int intervalSeconds;

    private final RestTemplate restTemplate;
    private ScheduledExecutorService scheduler;
    private final String hostname;
    private final String ip;

    public WebSocketHeartbeatSender() {
        this.restTemplate = new RestTemplate();
        this.hostname = resolveHostname();
        this.ip = resolveIp();
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("WebSocket 心跳上报未启用");
            return;
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat-sender");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("WebSocket 心跳上报已启动 - interval={}s, consoleUrl={}", intervalSeconds, consoleUrl);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            log.info("WebSocket 心跳上报已停止");
        }
    }

    /**
     * 发送一次心跳到 console
     */
    private void sendHeartbeat() {
        try {
            HeartbeatPayload payload = buildPayload();
            String url = consoleUrl + "/heartbeat/websocket/report";
            restTemplate.postForEntity(url, payload, Void.class);
            log.debug("WebSocket 心跳发送成功 - nodeId={}", payload.getNodeId());
        } catch (Exception e) {
            log.warn("WebSocket 心跳发送失败 - consoleUrl={}, error={}", consoleUrl, e.getMessage());
        }
    }

    /**
     * 构建心跳请求体
     */
    private HeartbeatPayload buildPayload() {
        HeartbeatPayload payload = new HeartbeatPayload();
        payload.setNodeId(resolveNodeId());
        payload.setServiceType("WEBSOCKET");
        payload.setHostname(hostname);
        payload.setIp(ip);
        payload.setPort(3063);
        payload.setTimestamp(System.currentTimeMillis());
        return payload;
    }

    /**
     * 解析节点ID：优先取环境变量 GMD_FAQ_NODE_ID，兜底使用 hostname
     */
    private String resolveNodeId() {
        String envNodeId = System.getenv("GMD_FAQ_NODE_ID");
        if (envNodeId != null && !envNodeId.isBlank()) {
            return envNodeId;
        }
        return hostname;
    }

    /**
     * 解析主机名：优先取环境变量 GMD_FAQ_HOST_NAME
     */
    private String resolveHostname() {
        String envName = System.getenv("GMD_FAQ_HOST_NAME");
        if (envName != null && !envName.isBlank()) {
            return envName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * 解析IP地址：优先取环境变量 GMD_FAQ_HOST_IP
     */
    private String resolveIp() {
        String envIp = System.getenv("GMD_FAQ_HOST_IP");
        if (envIp != null && !envIp.isBlank()) {
            return envIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }
}
