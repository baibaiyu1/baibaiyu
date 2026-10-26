package com.gmd.faq.server.main.engine.telemetry;

import com.gmd.faq.server.main.agent.service.AgentTelemetryService;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 观测故障不影响业务；异常只记录类型，不记录可能含密钥和正文的消息。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTelemetry {
    private final AgentTelemetryService store;
    public Model observe(Model model,long agentId,String operation) { return new TelemetryModel(model,this,agentId,operation); }
    public Model compaction(Model model,long agentId) {
        return observe(model instanceof TelemetryModel measured ? measured.delegate() : model,agentId,"COMPACTION");
    }
    public AgentTelemetryService.Scope scope(AgentContext owner,long agentId) {
        if (owner==null || owner.getUserInfo()==null || owner.getUserInfo().getId()==null || owner.getTenantId()==null || owner.getRunId()==null
                || owner.getRunId().length()>128 || owner.getThreadId()==null || !owner.getThreadId().matches("[0-9]{1,19}")) return null;
        try { return new AgentTelemetryService.Scope(owner.getTenantId(),owner.getUserInfo().getId(),Long.parseLong(owner.getThreadId()),owner.getRunId(),agentId); }
        catch (NumberFormatException e) { return null; }
    }
    public String begin(AgentTelemetryService.Scope scope,String operation,String model) {
        if (scope==null) return null;
        try { return store.begin(scope,operation,model); }
        catch (RuntimeException e) { log.warn("运行观测写入失败：{}",e.getClass().getSimpleName()); return null; }
    }
    public void transition(long agentId, String operation, String model, Throwable error) {
        String id = begin(scope(AgentContext.getIfExists().orElse(null), agentId), operation, model);
        end(id, "SUCCEEDED", System.nanoTime(), null, "MODEL", error);
    }
    public void end(String id,String state,long start,ChatUsage usage,String stage,Throwable error) {
        if (id==null) return;
        try { store.end(id,state,Math.max(0,(System.nanoTime()-start)/1_000_000),usage==null?null:
                new AgentTelemetryService.Usage(usage.getInputTokens(),usage.getOutputTokens(),usage.getReasoningTokens(),
                        usage.getCachedTokens(),usage.getCacheCreationTokens(),usage.getToolUsePromptTokens()),stage,error); }
        catch (RuntimeException e) { log.warn("运行观测完成记录失败：{}",e.getClass().getSimpleName()); }
    }
}
