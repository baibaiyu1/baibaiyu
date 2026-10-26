package com.gmd.faq.server.main.common.ws;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 描述：WebSocket 推送 HTTP 客户端 - 通过 REST 接口向 runner-websocket 服务发送推送消息
 *
 * 所有推送方法均为 fire-and-forget 异步模式：
 * 使用专用线程池执行 HTTP 请求，调用方线程立即返回，不阻塞业务主流程。
 * 租户上下文通过请求体 tenantId 字段跨进程传递，确保多租户隔离。
 *
 **/
@Slf4j
@Component
public class WebSocketClient {

    private static final ExecutorService WS_PUSH_EXECUTOR =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "ws-push-http");
                t.setDaemon(true);
                return t;
            });

    @Value("${websocket.enabled:true}")
    private boolean enabled;

    @Value("${websocket.base-url:http://localhost:3063}")
    private String baseUrl;

    /**
     * 推送消息给指定用户的所有客户端（集群广播）
     *
     * @param userId  用户 ID
     * @param type    消息类型
     * @param content 消息内容
     */
    public void pushToUserCluster(String userId, String type, Object content) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("type", type);
        body.put("content", content);
        body.put("tenantId", TenantUtils.getCurrentTenantId());
        doPostAsync("/ws/push/cluster/user", body);
    }

    /**
     * 集群广播消息给所有节点
     *
     * @param type    消息类型
     * @param content 消息内容
     */
    public void broadcastCluster(String type, Object content) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("content", content);
        body.put("tenantId", TenantUtils.getCurrentTenantId());
        doPostAsync("/ws/push/cluster/broadcast", body);
    }

    /**
     * 异步执行 HTTP POST，fire-and-forget 模式
     * 使用专用线程池避免阻塞 ForkJoinPool.commonPool()
     *
     * @param path 请求路径（相对于 baseUrl）
     * @param body 请求体
     */
    private void doPostAsync(String path, Map<String, Object> body) {
        if (!enabled) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            HttpResponse response = null;
            try {
                String url = baseUrl + path;
                response = HttpRequest.post(url)
                        .header("Content-Type", "application/json")
                        .body(JsonUtils.toJsonStr(body))
                        .timeout(5000)
                        .execute();
            } catch (Exception e) {
                log.error("WebSocket HTTP 推送失败：path={}", path, e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }, WS_PUSH_EXECUTOR);
    }
}
