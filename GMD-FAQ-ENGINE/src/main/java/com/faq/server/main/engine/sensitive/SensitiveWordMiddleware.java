package com.gmd.faq.server.main.engine.sensitive;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Flux;

public final class SensitiveWordMiddleware implements MiddlewareBase {
    private final SensitiveWordPolicy policy;
    public SensitiveWordMiddleware(SensitiveWordPolicy policy) { this.policy = policy; }
    @Override public int order() { return -200; }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (!policy.active()) return next.apply(input);
        return Flux.defer(() -> {
            AtomicBoolean hit = new AtomicBoolean();
            List<Msg> messages = input.msgs().stream().map(message -> {
                if (message.getRole() != MsgRole.USER) return message;
                String text = message.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText)
                        .collect(java.util.stream.Collectors.joining());
                if (policy.matches(text)) hit.set(true);
                // 一条消息拆成多个 TextBlock 仍按完整文本匹配；保留图片等非文本块。
                policy.input(text);
                if (policy.action() != SensitiveWordAction.REPLACE || !policy.matches(text)) return message;
                return message.withContent(SensitiveWordModel.filter(policy, List.of(
                        io.agentscope.core.model.ChatResponse.builder().content(message.getContent()).build()))
                        .getFirst().getContent());
            }).toList();
            Flux<AgentEvent> events = next.apply(new AgentInput(messages));
            if (policy.action() != SensitiveWordAction.WARN) {
                SensitiveWordDirectStream projection = new SensitiveWordDirectStream();
                events = events.concatMap(projection::apply);
            }
            events = events.concatMap(event -> {
                if (event instanceof AgentResultEvent ended
                        && Boolean.TRUE.equals(ended.getResult().getMetadata().get(SensitiveWordDirectReturnHook.WARNING_KEY)))
                    return Flux.just(warning("OUTPUT"), event);
                return Flux.just(event);
            });
            return hit.get() && policy.action() == SensitiveWordAction.WARN
                    ? Flux.concat(Flux.just(warning("INPUT")), events) : events;
        });
    }

    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!policy.active()) return next.apply(input);
        return Flux.defer(() -> {
            AtomicBoolean hit = new AtomicBoolean();
            var model = new SensitiveWordModel(input.model(), policy, () -> hit.set(true));
            return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(), model))
                    .concatWith(Flux.defer(() -> hit.get() ? Flux.just(warning("OUTPUT")) : Flux.empty()));
        });
    }

    private CustomEvent warning(String direction) {
        return new CustomEvent("SENSITIVE_WORD_WARNING", Map.of("direction", direction,
                "message", "INPUT".equals(direction) ? "输入内容命中敏感词，本次对话继续。" : "模型回复命中敏感词，请注意甄别。"));
    }
}
