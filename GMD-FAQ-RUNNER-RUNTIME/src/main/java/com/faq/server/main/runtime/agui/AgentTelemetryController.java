package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("${agentscope.agui.path-prefix:/agui}/telemetry")
@PermNeed({"agent:definition:list"})
public class AgentTelemetryController {
    private final SessionAccessGuard sessions;
    private final AgentTelemetryService telemetry;
    @GetMapping("/{threadId}")
    public R<Map<String,Object>> list(@PathVariable("threadId") Long threadId) {
        if (UserUtils.isChatKey() || UserUtils.isSk()) return R.fail("运行用量仅向登录账号开放");
        var session=sessions.requireRead(threadId);
        if (!Objects.equals(session.getTenantId(),UserUtils.getTenantId())) return R.fail("无权限访问其他租户会话");
        return R.data(Map.of("records",telemetry.list(session.getTenantId(),session.getUserId(),session.getId()),
                "runs",telemetry.runs(session.getTenantId(),session.getUserId(),session.getId())));
    }
    @GetMapping("/summary")
    public R<List<Map<String,Object>>> summary() {
        if (UserUtils.isChatKey() || UserUtils.isSk()) return R.fail("运行用量仅向登录账号开放");
        return R.data(telemetry.summary(UserUtils.getTenantId(),UserUtils.getId()));
    }
}
