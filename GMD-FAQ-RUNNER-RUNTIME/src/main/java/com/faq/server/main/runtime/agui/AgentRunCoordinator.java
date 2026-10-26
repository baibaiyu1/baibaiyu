package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentConversationQueue;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每个进程独立租约，手动运行、续跑和后台排队共用数据库门闩。 */
@Component
@RequiredArgsConstructor
public class AgentRunCoordinator {
    private final AgentConversationQueue queue;
    private final String nodeId = com.gmd.faq.server.main.common.consts.SysConst.CURRENT_NODE_ID + ":" + UUID.randomUUID();
    private final Map<String, Lease> active = new ConcurrentHashMap<>();
    private record Lease(AgentConversationQueue.Scope scope, String runId, Runnable stop) {}
    public String nodeId() { return nodeId; }
    public int activeCount() { return active.size(); }
    public void pause(long tenantId,long sessionId) { queue.pause(queue.sessionScope(tenantId,sessionId)); }

    public void begin(AgentContext principal, String runId, boolean resume, boolean reserved, Runnable stop) {
        if (principal.getThreadId() == null || !principal.getThreadId().matches("[0-9]{1,19}")) return;
        var scope = queue.sessionScope(principal.getTenantId(),Long.parseLong(principal.getThreadId()));
        if (reserved) {
            if (!queue.owns(scope,runId,nodeId)) throw new IllegalStateException("排队任务占用已失效");
        } else queue.begin(scope,runId,nodeId,resume);
        principal.setRunOwnerId(nodeId);
        active.put(principal.getThreadId(),new Lease(scope,runId,stop));
    }
    public void finish(String threadId, String runId, boolean waiting, boolean failed) {
        Lease lease = active.get(threadId);
        if (lease != null && lease.runId().equals(runId)) {
            queue.finish(lease.scope(),runId,waiting,failed);
            active.remove(threadId,lease);
        }
    }
    @Scheduled(fixedDelay = 10000)
    public void renew() {
        queue.renewIdle(nodeId);
        active.forEach((thread,lease) -> {
            try { if (!queue.renew(lease.scope(),lease.runId(),nodeId) || !queue.owns(lease.scope(),lease.runId(),nodeId)) lease.stop().run(); }
            catch (RuntimeException error) { lease.stop().run(); }
        });
        queue.expire();
    }
}
