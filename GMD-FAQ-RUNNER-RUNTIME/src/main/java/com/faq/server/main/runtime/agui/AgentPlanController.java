package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.plan.AgentPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("${agentscope.agui.path-prefix:/agui}/plan")
@PermNeed({"agent:chat:instruct"})
public class AgentPlanController {
    private final AgentPlanService plans;
    private final com.gmd.faq.server.main.agent.service.SessionAccessGuard sessions;

    @GetMapping("/{threadId}")
    public R<AgentPlanService.Document> get(@PathVariable("threadId") String threadId) {
        if (threadId == null || !threadId.matches("[0-9]{1,19}")) throw new IllegalArgumentException("方案仅支持正式会话");
        var session = sessions.requireWrite(Long.parseLong(threadId));
        if (!java.util.Objects.equals(session.getUserId(), UserUtils.getId())) throw new IllegalStateException("只能读取本人的方案");
        var owner = new AgentContext();
        owner.setThreadId(threadId); owner.setTenantId(UserUtils.getTenantId()); owner.setTenantCode(UserUtils.getTenantCode());
        owner.setPrincipal(UserUtils.getUserDetail());
        return R.data(plans.snapshot(owner));
    }
}
