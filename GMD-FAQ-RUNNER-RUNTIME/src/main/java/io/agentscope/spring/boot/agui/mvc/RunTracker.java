package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

/**
 * 描述：管理活跃智能体运行的生命周期，解耦 SSE 连接与 Agent Flux 管道。
 *
 * <p>核心职责：
 * <ul>
 *   <li>缓冲 Flux 事件，供多个 SSE 连接（含重连）消费</li>
 *   <li>SSE 断连不影响 Agent 后台运行</li>
 *   <li>提供显式停止、状态查询、活跃运行列表等管理能力</li>
 * </ul>
 *
 */
public class RunTracker implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RunTracker.class);

    /** 事件缓冲区最大容量 */
    private static final int MAX_BUFFER_SIZE = 10000;

    /** 运行完成后延迟清理时间 */
    private static final long CLEANUP_DELAY_MINUTES = 30;

    /** 避免长时间模型推理或工具执行期间，代理将空闲 SSE 连接关闭。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final AguiEventEncoder encoder;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "run-tracker-cleanup");
        t.setDaemon(true);
        return t;
    });

    public RunTracker(AguiEventEncoder encoder) {
        this.encoder = encoder;
        cleanupExecutor.scheduleAtFixedRate(this::sendHeartbeats,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, ActiveRun> entry : activeRuns.entrySet()) {
            ActiveRun run = entry.getValue();
            synchronized (run.bufferLock) {
                if (run.state == RunState.COMPLETED) {
                    continue;
                }
                for (SseEmitter emitter : List.copyOf(run.emitters)) {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (Exception e) {
                        logger.debug("Failed to send SSE heartbeat for thread {}: {}",
                                entry.getKey(), e.getMessage());
                        run.emitters.remove(emitter);
                    }
                }
            }
        }
    }

    /**
     * 注册一个活跃运行。
     *
     * @param threadId     会话 ID
     * @param agent        智能体实例（用于中断）
     * @param subscription Flux 订阅（用于 dispose）
     */
    public void registerRun(String threadId, Agent agent, Disposable subscription) {
        registerRun(threadId, agent, subscription, null);
    }

    public void registerRun(String threadId, Agent agent, Disposable subscription, RuntimeContext context) {
        synchronized (activeRuns) {
            ActiveRun existing = activeRuns.get(threadId);
            if (existing != null && existing.state != RunState.COMPLETED) {
                throw new IllegalStateException(
                        existing.state == RunState.STOPPING
                                ? "Agent is stopping, please wait for it to finish"
                                : "Agent is still running, please wait for it to finish");
            }
            if (existing != null) {
                synchronized (existing.bufferLock) {
                    deleteReplayArchive(existing);
                }
            }
            activeRuns.put(threadId, new ActiveRun(agent, subscription, context));
        }
        logger.debug("Registered active run for thread {}", threadId);
    }

    /**
     * 更新已有活跃运行的 subscription 引用，不替换 ActiveRun 及 buffer。
     *
     * @param threadId     会话 ID
     * @param subscription Flux 订阅
     */
    public void updateSubscription(String threadId, Disposable subscription) {
        ActiveRun run = activeRuns.get(threadId);
        if (run != null) {
            run.subscription = subscription;
            // 停止请求可能早于 Flux 订阅完成，补发一次中断信号，避免订阅时重置中断标志。
            if (run.state == RunState.STOPPING) {
                interruptAgent(run, threadId);
            }
            logger.debug("Updated subscription for thread {}", threadId);
        }
    }

    /**
     * 推送事件到缓冲区并分发给所有活跃 SSE 连接。
     *
     * @param threadId 会话 ID
     * @param event    AG-UI 事件
     */
    public void pushEvent(String threadId, AguiEvent event) {
        ActiveRun run = activeRuns.get(threadId);
        if (run == null) {
            return;
        }

        synchronized (run.bufferLock) {
            if (run.state == RunState.COMPLETED) {
                return;
            }
            BufferedEvent buffered = new BufferedEvent(run.nextSequence++, encoder.encodeToJson(event));
            if (run.eventBuffer.size() >= MAX_BUFFER_SIZE) {
                archiveEvent(run, run.eventBuffer.removeFirst());
            }
            run.eventBuffer.add(buffered);
            for (SseEmitter emitter : List.copyOf(run.emitters)) {
                if (!sendToEmitter(emitter, run, buffered)) {
                    logger.debug("Failed to send SSE to emitter for thread {}", threadId);
                    run.emitters.remove(emitter);
                }
            }
        }
    }

    /**
     * SSE 重连：回放已缓冲的事件并接续实时流。
     *
     * <p>回放和实时推送使用同一把锁，保证事件严格按 ID 发送一次。
     *
     * @param threadId 会话 ID
     * @param emitter  新的 SSE 连接
     */
    public void reconnect(String threadId, SseEmitter emitter, String lastEventId) {
        ActiveRun run = activeRuns.get(threadId);
        if (run == null) {
            sendReplayGap(emitter, "运行记录已失效，无法恢复完整对话，请刷新会话");
            return;
        }

        synchronized (run.bufferLock) {
            long cursor = 0;
            if (lastEventId != null && !lastEventId.isBlank()) {
                String prefix = run.runToken + ":";
                if (!lastEventId.startsWith(prefix)) {
                    sendReplayGap(emitter, "运行已变更，无法恢复完整对话，请刷新会话");
                    return;
                }
                try {
                    cursor = Long.parseLong(lastEventId.substring(prefix.length()));
                } catch (NumberFormatException e) {
                    sendReplayGap(emitter, "无效的事件游标，无法恢复完整对话，请刷新会话");
                    return;
                }
            }
            long earliest = run.eventBuffer.isEmpty()
                    ? run.nextSequence : run.eventBuffer.getFirst().sequence();
            if (cursor >= run.nextSequence || cursor < 0
                    || (cursor < earliest - 1 && run.archivedThrough != earliest - 1)) {
                sendReplayGap(emitter, "事件缓存已过期，无法恢复完整对话，请刷新会话");
                return;
            }

            for (SseEmitter old : List.copyOf(run.emitters)) {
                try {
                    old.complete();
                } catch (Exception ignored) {
                }
            }
            run.emitters.clear();

            try {
                // 较早的增量事件转存临时文件，不能因内存窗口滚动而截断仍在运行的回复。
                if (cursor < earliest - 1 && !replayArchive(emitter, run, cursor)) {
                    emitter.complete();
                    return;
                }
                for (BufferedEvent buffered : run.eventBuffer) {
                    if (buffered.sequence() > cursor && !sendToEmitter(emitter, run, buffered)) {
                        emitter.complete();
                        return;
                    }
                }
                emitter.send(SseEmitter.event().data("{\"type\":\"REPLAY_CAUGHT_UP\"}",
                        MediaType.APPLICATION_JSON));
                if (run.state == RunState.COMPLETED) {
                    emitter.complete();
                } else {
                    run.emitters.add(emitter);
                }
            } catch (Exception e) {
                logger.debug("Failed to reconnect SSE for thread {}: {}", threadId, e.getMessage());
                sendReplayGap(emitter, "事件记录暂不可用，正在恢复已保存的对话");
            }
        }
    }

    public void reconnect(String threadId, SseEmitter emitter) {
        reconnect(threadId, emitter, null);
    }

    /**
     * 强制停止指定会话的智能体运行。
     *
     * @param threadId 会话 ID
     */
    public void stopRun(String threadId) {
        ActiveRun run = activeRuns.get(threadId);
        if (run == null) {
            return;
        }
        synchronized (run.bufferLock) {
            if (run.state == RunState.COMPLETED) {
                return;
            }
            run.state = RunState.STOPPING;
        }
        interruptAgent(run, threadId);
        // 不在这里关闭 SSE 或标记完成，必须等待 Agent 流真正结束后再释放运行占用。
        logger.info("Stop requested for thread {}", threadId);
    }

    private void interruptAgent(ActiveRun run, String threadId) {
        try {
            if (run.agent instanceof ReActAgent react && run.runtimeContext != null) {
                react.interrupt(run.runtimeContext);
            } else {
                run.agent.interrupt();
            }
        } catch (Exception e) {
            logger.warn("Error interrupting agent for thread {}: {}", threadId, e.getMessage());
        }
    }

    /**
     * 获取指定会话的运行状态。
     *
     * @param threadId 会话 ID
     * @return 是否正在运行
     */
    public boolean getStatus(String threadId) {
        ActiveRun run = activeRuns.get(threadId);
        return run != null && run.state != RunState.COMPLETED;
    }

    /** 获取运行生命周期状态，停止请求期间仍返回 STOPPING。 */
    public RunState getRunState(String threadId) {
        ActiveRun run = activeRuns.get(threadId);
        return run == null ? RunState.COMPLETED : run.state;
    }

    /** 只返回运行标识，不暴露用户上下文或工具参数。 */
    public RunSnapshot snapshot(String threadId) {
        ActiveRun run = activeRuns.get(threadId);
        if (run == null) return new RunSnapshot(null, RunState.COMPLETED, null);
        return new RunSnapshot(run.runId, run.state, run.startedAt);
    }

    /**
     * 获取所有活跃运行的 threadId 列表。
     *
     * @return 活跃 threadId 集合
     */
    public Set<String> getActiveRuns() {
        return activeRuns.entrySet().stream()
                .filter(e -> e.getValue().state != RunState.COMPLETED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 标记运行完成，启动延迟清理。
     *
     * @param threadId 会话 ID
     */
    public void markCompleted(String threadId) {
        ActiveRun run = activeRuns.get(threadId);
        if (run == null) {
            return;
        }
        if (run.state == RunState.COMPLETED) {
            return;
        }
        synchronized (run.bufferLock) {
            if (run.state == RunState.COMPLETED) {
                return;
            }
            run.state = RunState.COMPLETED;
            closeReplayWriter(run);
            for (SseEmitter emitter : List.copyOf(run.emitters)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            run.emitters.clear();
        }

        // 延迟清理
        cleanupExecutor.schedule(() -> {
            // 只清理原运行记录，避免新运行已经注册后被旧任务误删。
            activeRuns.remove(threadId, run);
            synchronized (run.bufferLock) {
                deleteReplayArchive(run);
            }
            logger.debug("Cleaned up completed run for thread {}", threadId);
        }, CLEANUP_DELAY_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 移除 SSE emitter（断开时调用）。
     *
     * @param threadId 会话 ID
     * @param emitter  要移除的 emitter
     */
    public void removeEmitter(String threadId, SseEmitter emitter) {
        ActiveRun run = activeRuns.get(threadId);
        if (run != null) {
            synchronized (run.bufferLock) {
                run.emitters.remove(emitter);
            }
        }
    }

    private boolean sendToEmitter(SseEmitter emitter, ActiveRun run, BufferedEvent buffered) {
        try {
            emitter.send(SseEmitter.event()
                    .id(run.runToken + ":" + buffered.sequence())
                    .data(buffered.json(), MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            logger.debug("Failed to send event to emitter: {}", e.getMessage());
            return false;
        }
    }

    /** 只在超过内存窗口后落盘；归档失败仍保留最近事件和实时输出，由客户端补查历史。 */
    private void archiveEvent(ActiveRun run, BufferedEvent event) {
        if (run.archiveFailed) return;
        try {
            if (run.replayFile == null) {
                run.replayFile = Files.createTempFile("gmd-agui-replay-", ".jsonl");
                run.replayWriter = Files.newBufferedWriter(run.replayFile, StandardCharsets.UTF_8);
            }
            run.replayWriter.write(event.json());
            run.replayWriter.newLine();
            run.archivedThrough = event.sequence();
        } catch (IOException e) {
            run.archiveFailed = true;
            deleteReplayArchive(run);
            logger.warn("Unable to archive AG-UI replay events", e);
        }
    }

    private boolean replayArchive(SseEmitter emitter, ActiveRun run, long cursor) throws IOException {
        if (run.replayWriter != null) run.replayWriter.flush();
        try (var reader = Files.newBufferedReader(run.replayFile, StandardCharsets.UTF_8)) {
            long sequence = 0;
            String json;
            while ((json = reader.readLine()) != null) {
                sequence++;
                if (sequence > cursor && !sendToEmitter(emitter, run, new BufferedEvent(sequence, json))) {
                    return false;
                }
            }
            if (sequence != run.archivedThrough) throw new IOException("Incomplete AG-UI replay archive");
        }
        return true;
    }

    private void closeReplayWriter(ActiveRun run) {
        if (run.replayWriter == null) return;
        try {
            run.replayWriter.close();
        } catch (IOException e) {
            run.archiveFailed = true;
            run.archivedThrough = 0;
            logger.debug("Failed to close AG-UI replay archive", e);
        } finally {
            run.replayWriter = null;
        }
    }

    private void deleteReplayArchive(ActiveRun run) {
        closeReplayWriter(run);
        run.archivedThrough = 0;
        if (run.replayFile == null) return;
        try {
            Files.deleteIfExists(run.replayFile);
        } catch (IOException e) {
            logger.warn("Failed to clean up AG-UI replay archive", e);
        }
        run.replayFile = null;
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
        synchronized (activeRuns) {
            for (ActiveRun run : activeRuns.values()) {
                synchronized (run.bufferLock) {
                    run.state = RunState.COMPLETED;
                    for (SseEmitter emitter : List.copyOf(run.emitters)) emitter.complete();
                    run.emitters.clear();
                    deleteReplayArchive(run);
                }
            }
            activeRuns.clear();
        }
    }

    private void sendReplayGap(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().data(
                    "{\"type\":\"REPLAY_GAP\",\"message\":\"" + message + "\"}",
                    MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            logger.debug("Failed to send replay gap: {}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }

    /**
     * 描述：活跃运行的内部状态。
     */
    private static class ActiveRun {

        final Agent agent;
        final RuntimeContext runtimeContext;
        final String runId;
        final java.time.Instant startedAt = java.time.Instant.now();
        Disposable subscription;
        final String runToken = UUID.randomUUID().toString();
        final Deque<BufferedEvent> eventBuffer = new ArrayDeque<>();
        Path replayFile;
        BufferedWriter replayWriter;
        long archivedThrough;
        boolean archiveFailed;
        final List<SseEmitter> emitters = new ArrayList<>();
        final Object bufferLock = new Object();
        long nextSequence = 1;
        volatile RunState state = RunState.RUNNING;

        ActiveRun(Agent agent, Disposable subscription, RuntimeContext runtimeContext) {
            this.agent = agent;
            this.subscription = subscription;
            this.runtimeContext = runtimeContext;
            var context = runtimeContext == null ? null
                    : runtimeContext.get(com.gmd.faq.server.main.engine.agui.AgentContext.class);
            this.runId = context == null ? null : context.getRunId();
        }
    }

    private record BufferedEvent(long sequence, String json) {}

    public record RunSnapshot(String runId, RunState state, java.time.Instant startedAt) {}

    /** 运行生命周期状态。 */
    public enum RunState {
        RUNNING,
        STOPPING,
        COMPLETED
    }
}
