package com.gmd.faq.server.main.engine.workspace.hook;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.subagent.SubAgentTraceEvent;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.engine.tool.ToolResultImageSupport;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 在日志和流式事件之前处理图片，并同步更新真正进入模型记忆的工具消息。 */
@Component
@RequiredArgsConstructor
public class ToolResultImageHook implements Hook {
    private final ToolResultImageSupport imageSupport;

    @Override
    public int priority() { return 90; }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent acting) || !(event.getAgent() instanceof AgentBase agent)
                || acting.getToolResult() == null || acting.getToolResultMsg() == null) return Mono.just(event);
        ToolResultBlock result = acting.getToolResult();
        if (result.getMetadata().containsKey(SubAgentTraceEvent.METADATA_KEY)
                || result.getMetadata().containsKey(SubAgentTraceEvent.FINAL_RESULT_METADATA_KEY)) return Mono.just(event);
        String tenantCode = AgentMetadataStore.get(agent.getAgentId(), "tenantCode");
        String threadId = AgentMetadataStore.get(agent.getAgentId(), "threadId");
        if (tenantCode == null || threadId == null || !tenantCode.matches("[A-Za-z0-9_-]+")
                || !threadId.matches("[A-Za-z0-9_-]+")) return Mono.just(event);
        Path workspace = Path.of(SysConst.getWorkspacePath(tenantCode), threadId);
        ToolResultBlock normalized = imageSupport.normalize(result, workspace);
        if (normalized != result) {
            acting.setToolResult(normalized);
            List<ContentBlock> blocks = acting.getToolResultMsg().getContent().stream()
                    .map(block -> block instanceof ToolResultBlock ? (ContentBlock) normalized : block).toList();
            Msg message = acting.getToolResultMsg();
            acting.setToolResultMsg(Msg.builder().id(message.getId()).name(message.getName())
                    .role(message.getRole()).content(blocks).metadata(message.getMetadata())
                    .timestamp(message.getTimestamp()).build());
        }
        return Mono.just(event);
    }
}
