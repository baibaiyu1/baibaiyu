package com.gmd.faq.server.main.engine.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/** 长期记忆读取本次调用的状态，避免旧 Hook 绑定到匿名默认会话。 */
public final class SessionLongTermMemory implements MiddlewareBase {
    private static final Logger LOG = LoggerFactory.getLogger(SessionLongTermMemory.class);
    private static final String RETRIEVED_KEY = "gmd.long-term-memory.retrieved";
    private static final Scheduler RECORD_SCHEDULER = Schedulers.newBoundedElastic(1, 3, "gmd-memory-record");
    private final LongTermMemory memory;

    public SessionLongTermMemory(LongTermMemory memory) {
        this.memory = memory;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.msgs() == null ? List.of() : input.msgs();
        Msg query = messages.stream().filter(msg -> msg.getRole() == MsgRole.USER
                && !msg.getContent().isEmpty()).reduce((first, last) -> last).orElse(null);
        Mono<String> retrieved = query == null ? Mono.just("")
                : Mono.defer(() -> memory.retrieve(query)).defaultIfEmpty("").onErrorResume(error -> {
                    LOG.warn("长期记忆检索失败: {}", error.getMessage());
                    return Mono.just("");
                });
        return retrieved.flatMapMany(text -> {
            rc.put(RETRIEVED_KEY, text);
            return Flux.deferContextual(context -> next.apply(input).doOnNext(event -> {
                if (event instanceof AgentResultEvent result
                        && result.getResult().getGenerateReason() != GenerateReason.REASONING_STOP_REQUESTED
                        && result.getResult().getGenerateReason() != GenerateReason.PERMISSION_ASKING
                        && result.getResult().getGenerateReason() != GenerateReason.TOOL_SUSPENDED
                        && rc.getAgentState() != null) {
                    List<Msg> snapshot = rc.getAgentState().getContext().stream()
                            .filter(msg -> !Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))).toList();
                    if (!snapshot.isEmpty()) record(snapshot, context);
                }
            }));
        });
    }

    private void record(List<Msg> snapshot, ContextView context) {
        Mono.defer(() -> memory.record(snapshot)).subscribeOn(RECORD_SCHEDULER)
                .contextWrite(current -> current.putAll(context))
                .subscribe(ignored -> {}, error -> LOG.warn("长期记忆记录失败: {}", error.getMessage()));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext rc, String prompt) {
        String text = rc.get(RETRIEVED_KEY) instanceof String value ? value : "";
        return Mono.just((prompt == null ? "" : prompt)
                + (text.isBlank() ? "" : "\n\n" + LongTermMemoryTools.wrap(text)));
    }
}
