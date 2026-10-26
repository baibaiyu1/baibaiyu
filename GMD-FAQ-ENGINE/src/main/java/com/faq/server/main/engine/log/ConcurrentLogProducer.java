package com.gmd.faq.server.main.engine.log;

import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.util.BeanUtils;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 描述：日志生产者
 *
 **/
public class ConcurrentLogProducer {
    static final class Barrier extends ChatMessage {
        final java.util.concurrent.CompletableFuture<Void> completed = new java.util.concurrent.CompletableFuture<>();
    }

    /** 运行结束前等待此前入队日志落库，下一轮不能越过上一轮正文。 */
    public static reactor.core.publisher.Mono<Void> flush() {
        return reactor.core.publisher.Mono.defer(() -> {
            if (logConsumer == null) return reactor.core.publisher.Mono.empty();
            Barrier barrier = new Barrier();
            if (!queue.offer(barrier)) return reactor.core.publisher.Mono.error(new IllegalStateException("聊天日志队列已满"));
            return reactor.core.publisher.Mono.fromFuture(barrier.completed).timeout(java.time.Duration.ofSeconds(30));
        });
    }
    private static final Object INIT_LOCK = new Object();
    @Getter
    private static final LinkedBlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(50000);
    private static volatile ConcurrentLogConsumer logConsumer = null;

    public static void pushLog(ChatMessage log) {
        if (logConsumer == null) {
            synchronized (INIT_LOCK) {
                if (logConsumer == null) {
                    logConsumer = BeanUtils.getBean(ConcurrentLogConsumer.class);
                    logConsumer.tryStart();
                }
            }
        }

        log.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));

        try {
            // 尝试加入队列, 如果队列已满，则阻塞, 等待3毫秒
            if (!queue.offer(log, 3, TimeUnit.MILLISECONDS)) throw new IllegalStateException("聊天日志队列已满");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("聊天日志入队被中断",error);
        }
    }

}
