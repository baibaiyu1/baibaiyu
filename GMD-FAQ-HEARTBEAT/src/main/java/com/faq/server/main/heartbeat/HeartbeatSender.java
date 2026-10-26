package com.gmd.faq.server.main.heartbeat;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 描述：心跳定时发送器
 * 定期向 console 发送心跳，失败仅记录日志不中断调度
 *
 **/
public class HeartbeatSender {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSender.class);

    private final HeartbeatProperties properties;
    private final RestTemplate restTemplate;
    private ScheduledExecutorService scheduler;

    private final String hostname;
    private final String ip;

    public HeartbeatSender(HeartbeatProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        this.hostname = resolveHostname();
        this.ip = resolveIp();
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            log.info("心跳上报未启用 (heartbeat.enabled=false)");
            return;
        }
        if (properties.getServiceType() == null || properties.getServiceType().isBlank()) {
            log.warn("心跳上报未配置 serviceType，跳过启动");
            return;
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-sender");
            t.setDaemon(true);
            return t;
        });

        // 启动后立即发送首次心跳，之后按配置间隔定时发送
        int interval = properties.getIntervalSeconds();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, interval, TimeUnit.SECONDS);

        log.info("心跳上报已启动 - nodeId={}, serviceType={}, interval={}s, consoleUrl={}",
                properties.getNodeId(), properties.getServiceType(), interval, properties.getConsoleUrl());
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
            log.info("心跳上报已停止");
        }
    }

    /**
     * 发送一次心跳到 console
     */
    private void sendHeartbeat() {
        try {
            HeartbeatPayload payload = buildPayload();
            String url = properties.getConsoleUrl() + "/heartbeat/report";
            restTemplate.postForEntity(url, payload, Void.class);
            log.debug("心跳发送成功 - nodeId={}, serviceType={}", payload.getNodeId(), payload.getServiceType());
        } catch (Exception e) {
            log.warn("心跳发送失败 - consoleUrl={}, error={}", properties.getConsoleUrl(), e.getMessage());
        }
    }

    /**
     * 构建心跳请求体
     */
    private HeartbeatPayload buildPayload() {
        HeartbeatPayload payload = new HeartbeatPayload();
        payload.setNodeId(properties.getNodeId());
        payload.setServiceType(properties.getServiceType());
        payload.setHostname(hostname);
        payload.setIp(ip);
        payload.setPort(properties.getPort());
        payload.setTimestamp(System.currentTimeMillis());
        return payload;
    }

    /**
     * 解析主机名：优先取环境变量 HOST_NAME，兜底使用 InetAddress
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
     * 解析IP地址：优先取环境变量 HOST_IP，兜底使用 InetAddress
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
