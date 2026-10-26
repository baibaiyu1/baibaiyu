package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("${agentscope.agui.path-prefix:/agui}/queue")
@PermNeed({"agent:chat:instruct"})
public class AgentQueueController {
    private final AgentConversationQueue queue;
    private final SessionAccessGuard sessions;
    private final AgentDefinitionService agents;
    private final ResourceVisibilityService visibility;
    private final AgentRunCoordinator coordinator;
    public record Submit(String requestId,String content,AgentConversationQueue.Options options) {}
    public record Edit(String content) {}
    public record Steer(String runId) {}

    @GetMapping("/{threadId}")
    public R<AgentConversationQueue.Snapshot> list(@PathVariable("threadId") long threadId) {
        return R.data(queue.snapshot(scope(threadId,false)));
    }
    @PostMapping("/{threadId}")
    public R<AgentConversationQueue.Queued> submit(@PathVariable("threadId") long threadId,@RequestBody Submit request) {
        var scope=scope(threadId,true);
        var item=queue.enqueueOnNode(scope,request.requestId(),request.content(),request.options(),coordinator.nodeId());
        return R.data(item);
    }
    @PutMapping("/{threadId}/{id}")
    public R<Boolean> edit(@PathVariable("threadId") long threadId,@PathVariable("id") long id,@RequestBody Edit request) {
        queue.edit(scope(threadId,true),id,request.content()); return R.data(true);
    }
    @DeleteMapping("/{threadId}/{id}")
    public R<Boolean> cancel(@PathVariable("threadId") long threadId,@PathVariable("id") long id) {
        queue.cancel(scope(threadId,false),id); return R.data(true);
    }
    @PostMapping("/{threadId}/{id}/steer")
    public R<AgentConversationQueue.Queued> steer(@PathVariable("threadId") long threadId,@PathVariable("id") long id,
                                                @RequestBody Steer request) {
        return R.data(queue.steer(scope(threadId,true),id,request.runId(),coordinator.nodeId()));
    }
    @PostMapping("/{threadId}/continue")
    public R<Boolean> continueQueue(@PathVariable("threadId") long threadId) {
        queue.continueQueue(scope(threadId,true),coordinator.nodeId()); return R.data(true);
    }
    private AgentConversationQueue.Scope scope(long id,boolean active) {
        if (UserUtils.getUserDetail()==null || UserUtils.isChatKey() || UserUtils.isSk()) throw new IllegalStateException("消息排队仅向登录账号开放");
        ChatSession session=active?sessions.requireActiveWrite(id):sessions.requireWrite(id);
        if (!Objects.equals(session.getUserId(),UserUtils.getId()) || !Objects.equals(session.getTenantId(),UserUtils.getTenantId()))
            throw new IllegalStateException("只能操作本人当前租户的消息队列");
        if (active) {
            AgentDefinition agent=agents.getById(session.getAgentId());
            if (agent==null || !Boolean.TRUE.equals(agent.getEnabled())) throw new IllegalStateException("智能体不存在或已禁用");
            visibility.assertUsable("AGENT",agent.getId());
            if (!Boolean.TRUE.equals(agent.getPublished()) && !com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.isManageable(agent) && !UserUtils.isAdmin())
                throw new IllegalStateException("智能体尚未发布");
        }
        return new AgentConversationQueue.Scope(session.getTenantId(),session.getUserId(),id);
    }
}
