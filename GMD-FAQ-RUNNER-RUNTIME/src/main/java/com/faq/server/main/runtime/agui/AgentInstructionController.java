package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.AgentInstructionService;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.mvc.RunTracker;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 补充要求仅限登录账号的本人正式会话，管理范围不会授予插入他人任务的权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("${agentscope.agui.path-prefix:/agui}/instructions")
@PermNeed({"agent:chat:instruct"})
public class AgentInstructionController {
    private final SessionAccessGuard sessions;
    private final AgentDefinitionService agents;
    private final ResourceVisibilityService visibility;
    private final AgentInstructionService inbox;
    private final AguiMvcController agui;

    public record Submit(String runId, String requestId, String content) {}
    public record Inbox(boolean enabled, String runId, boolean accepting,
                        List<AgentInstructionService.Instruction> messages) {}

    @GetMapping("/{threadId}")
    public R<Inbox> list(@PathVariable("threadId") String threadId) {
        ChatSession session = ownSession(threadId, false);
        var snapshot = agui.getRunTracker().snapshot(threadId);
        if (snapshot.runId() != null && snapshot.state() != RunTracker.RunState.RUNNING)
            inbox.finish(scope(session, snapshot.runId()), "ENDED");
        return R.data(new Inbox(true, snapshot.runId(), snapshot.runId() != null
                && snapshot.state() == RunTracker.RunState.RUNNING && inbox.isActive(scope(session, snapshot.runId())),
                inbox.list(session.getTenantId(), session.getUserId(), session.getId())));
    }

    @PostMapping("/{threadId}")
    public R<AgentInstructionService.Instruction> submit(@PathVariable("threadId") String threadId, @RequestBody Submit request) {
        ChatSession session = ownSession(threadId, true);
        AgentDefinition agent = agents.getById(session.getAgentId());
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) return R.fail("智能体不存在或已禁用");
        visibility.assertUsable("AGENT", agent.getId());
        if (!Boolean.TRUE.equals(agent.getPublished()) && !com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.isManageable(agent)
                && !UserUtils.isAdmin()) return R.fail("智能体尚未发布");
        var active = agui.getRunTracker().snapshot(threadId);
        if (active.state() != RunTracker.RunState.RUNNING || active.runId() == null
                || !Objects.equals(active.runId(), request.runId()))
            return R.fail("目标运行已结束、正在停止或不在当前节点，请刷新运行状态");
        try {
            return R.data(inbox.receive(scope(session, active.runId()), request.requestId(), request.content()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    private ChatSession ownSession(String threadId, boolean active) {
        if (UserUtils.getUserDetail() == null || UserUtils.isChatKey() || UserUtils.isSk())
            throw new IllegalStateException("补充要求仅向登录账号开放");
        if (threadId == null || !threadId.matches("[0-9]{1,19}"))
            throw new IllegalArgumentException("补充要求仅支持正式会话");
        long sessionId;
        try { sessionId = Long.parseLong(threadId); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("会话ID无效"); }
        ChatSession session = active ? sessions.requireActiveWrite(sessionId) : sessions.requireWrite(sessionId);
        if (!Objects.equals(session.getUserId(), UserUtils.getId())
                || !Objects.equals(session.getTenantId(), UserUtils.getTenantId()))
            throw new IllegalStateException("只能向本人当前租户下的会话补充要求");
        return session;
    }

    private static AgentInstructionService.Scope scope(ChatSession session, String runId) {
        return new AgentInstructionService.Scope(session.getTenantId(), session.getUserId(), session.getId(), runId);
    }
}
