package com.gmd.faq.server.main.websocket.endpoint;

import com.gmd.faq.server.main.websocket.endpoint.dto.PushRequest;
import com.gmd.faq.server.main.websocket.service.WebSocketPushService;
import com.gmd.faq.server.main.common.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 描述：WebSocket 推送 REST 控制器 - 供其他内部服务通过 HTTP 发送 WebSocket 消息
 *
 * 异步非阻塞原理：
 * Spring MVC 检测到返回类型为 Mono 后，自动切到 Servlet 3.1 异步模式，
 * Tomcat HTTP 线程立刻释放。Mono.fromRunnable 通过 subscribeOn 调度到
 * WebFlux 内置的 boundedElastic 弹性线程池执行，按需扩缩容，适合 I/O 密集型短任务。
 *
 **/
@Slf4j
@RestController
@RequestMapping("/ws/push")
@RequiredArgsConstructor
public class WebSocketPushController {

    private final WebSocketPushService pushService;

    /**
     * 从请求体恢复租户上下文，确保跨进程调用时多租户隔离不丢失
     */
    private void setTenantContext(PushRequest req) {
        if (req.getTenantId() != null) {
            TenantUtils.setCurrentTenant(req.getTenantId(), null);
        }
    }

    // ==================== 本地推送 ====================

    /**
     * 推送消息给指定客户端（本地节点）
     *
     * @param req clientId、type、content 必填
     */
    @PostMapping("/client")
    public Mono<ResponseEntity<Object>> pushToClient(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.pushToClient(req.getClientId(), req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("推送客户端失败：clientId={}", req.getClientId(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * 推送消息给指定用户的所有客户端（本地节点）
     *
     * @param req userId、type、content 必填
     */
    @PostMapping("/user")
    public Mono<ResponseEntity<Object>> pushToUser(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.pushToUser(req.getUserId(), req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("推送用户失败：userId={}", req.getUserId(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * 广播消息给所有在线客户端（本地节点）
     *
     * @param req type、content 必填
     */
    @PostMapping("/broadcast")
    public Mono<ResponseEntity<Object>> broadcast(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.broadcast(req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("广播消息失败", e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    // ==================== 集群推送 ====================

    /**
     * 推送消息给指定客户端（集群，通过 Redis Pub/Sub 广播到各节点）
     *
     * @param req clientId、type、content 必填
     */
    @PostMapping("/cluster/client")
    public Mono<ResponseEntity<Object>> pushToClientCluster(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.pushToClientCluster(req.getClientId(), req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("集群推送客户端失败：clientId={}", req.getClientId(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * 推送消息给指定用户的所有客户端（集群）
     *
     * @param req userId、type、content 必填
     */
    @PostMapping("/cluster/user")
    public Mono<ResponseEntity<Object>> pushToUserCluster(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.pushToUserCluster(req.getUserId(), req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("集群推送用户失败：userId={}", req.getUserId(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * 推送消息给指定用户编码的所有客户端（集群）
     *
     * @param req userCode、type、content 必填
     */
    @PostMapping("/cluster/usercode")
    public Mono<ResponseEntity<Object>> pushToUserCodeCluster(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.pushToUserCodeCluster(req.getUserCode(), req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("集群推送用户编码失败：userCode={}", req.getUserCode(), e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * 广播消息到所有节点（集群）
     *
     * @param req type、content 必填
     */
    @PostMapping("/cluster/broadcast")
    public Mono<ResponseEntity<Object>> broadcastCluster(@RequestBody PushRequest req) {
        return Mono.fromRunnable(() -> {
                    setTenantContext(req);
                    pushService.broadcastCluster(req.toWsMessage());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()))
                .onErrorResume(e -> {
                    log.error("集群广播消息失败", e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
}
