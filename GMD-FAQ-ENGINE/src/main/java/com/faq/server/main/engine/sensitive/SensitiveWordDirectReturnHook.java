package com.gmd.faq.server.main.engine.sensitive;

import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import java.util.List;
import reactor.core.publisher.Mono;

/** 过滤直接返回的正文副本，保留来源工具的实际结果和产物。 */
public final class SensitiveWordDirectReturnHook implements Hook {
    static final String WARNING_KEY = "gmd.sensitive.direct-warning";
    private final SensitiveWordPolicy policy;
    public SensitiveWordDirectReturnHook(SensitiveWordPolicy policy) { this.policy = policy; }
    // SDK 按升序执行 Hook，必须早于 ChatLogHook 保存回复副本。
    @Override public int priority() { return -200; }
    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!policy.active() || !(event instanceof PostCallEvent ended)
                || !Boolean.TRUE.equals(ended.getFinalMessage().getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT)))
            return Mono.just(event);
        Msg message = ended.getFinalMessage();
        if (policy.action() == SensitiveWordAction.WARN) {
            if (SensitiveWordModel.matches(policy, message.getContent())) {
                var metadata = new java.util.HashMap<>(message.getMetadata()); metadata.put(WARNING_KEY, true);
                ended.setFinalMessage(message.withMetadata(metadata));
            }
        } else {
            var filtered = SensitiveWordModel.filter(policy, List.of(ChatResponse.builder().content(message.getContent()).build()));
            ended.setFinalMessage(message.withContent(filtered.getFirst().getContent()));
        }
        return Mono.just(event);
    }
}
