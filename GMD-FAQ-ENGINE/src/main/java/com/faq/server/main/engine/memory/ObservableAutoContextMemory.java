package com.gmd.faq.server.main.engine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.engine.formatter.ToolCallSanitizingModel;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 使用 2.0 上下文压缩器，并通过每次调用的 RuntimeContext 推送压缩生命周期。 */
public class ObservableAutoContextMemory implements MiddlewareBase {
    public static final String LISTENER_KEY = "gmd.compression.listener";
    public static final String COUNT_KEY = "gmd.compression.count";
    private static final String FINGERPRINT_KEY = "gmd.compression.fingerprint";
    private final long maxToken;
    private final CompactionConfig config;
    private final ConversationCompactor compactor;

    public ObservableAutoContextMemory(JsonNode settings, Model model) {
        maxToken = Math.max(1, JsonUtils.getLongValue(settings, "maxToken", 131072L));
        double ratio = Math.max(0.01, Math.min(1, JsonUtils.getDoubleValue(settings, "tokenRatio", 0.75)));
        config = CompactionConfig.builder()
                .triggerTokens((int) Math.min(Integer.MAX_VALUE, Math.max(1, maxToken * ratio)))
                .triggerMessages(Math.max(1, JsonUtils.getIntValue(settings, "msgThreshold", 100)))
                .keepMessages(Math.max(1, JsonUtils.getIntValue(settings, "lastKeep", 50)))
                .keepTokens(0)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .prune(null)
                .truncateArgs(null)
                .build();
        compactor = new ConversationCompactor(new ToolCallSanitizingModel(model), null);
    }

    @Override
    public int order() { return 2; }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentContext context = rc.get(AgentContext.class);
            if (context != null && !context.isMemoryActive()) return next.apply(input);
            List<Msg> conversation = input.messages().stream()
                    .filter(msg -> msg.getRole() != MsgRole.SYSTEM
                            && !Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))).toList();
            ContextUsageSnapshot before = getContextUsageSnapshot(conversation);
            if (before.usedTokens() < before.tokenThreshold()
                    && before.messageCount() < before.messageThreshold()) {
                return next.apply(input);
            }
            String fingerprint = before.usedTokens() + ":" + before.messageCount();
            if (fingerprint.equals(rc.get(FINGERPRINT_KEY))) {
                return next.apply(input);
            }
            notifyLifecycle(rc, new CompressionLifecycleEvent(CompressionStatus.STARTED, before, before, false));
            return compactor.compactIfNeeded(rc, conversation, config, agent.getName(), rc.getSessionId())
                    .doOnError(error -> notifyLifecycle(rc, new CompressionLifecycleEvent(
                            CompressionStatus.FAILED, before, before, false)))
                    .flatMapMany(result -> {
                        List<Msg> messages = result.orElse(conversation);
                        ContextUsageSnapshot after = getContextUsageSnapshot(messages);
                        boolean failed = result.isPresent() && !messages.isEmpty()
                                && (messages.getFirst().getTextContent().contains("(Summary unavailable)")
                                || messages.getFirst().getTextContent().contains("(Summarization failed:"));
                        boolean reduced = !failed && (after.usedTokens() < before.usedTokens()
                                || after.messageCount() < before.messageCount());
                        if (!reduced) rc.put(FINGERPRINT_KEY, fingerprint);
                        if (result.isPresent() && reduced) {
                            rc.getAgentState().contextMutable().clear();
                            rc.getAgentState().contextMutable().addAll(messages);
                            int count = rc.get(COUNT_KEY) instanceof Number value ? value.intValue() : 0;
                            rc.put(COUNT_KEY, count + 1);
                        }
                        notifyLifecycle(rc, new CompressionLifecycleEvent(
                                failed ? CompressionStatus.FAILED : result.isPresent() && reduced
                                        ? CompressionStatus.FINISHED : CompressionStatus.SKIPPED,
                                before, reduced ? after : before, result.isPresent() && reduced));
                        List<Msg> updated = new ArrayList<>();
                        input.messages().stream().filter(msg -> msg.getRole() == MsgRole.SYSTEM).forEach(updated::add);
                        updated.addAll(reduced ? messages : conversation);
                        input.messages().stream().filter(msg -> msg.getRole() != MsgRole.SYSTEM
                                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))).forEach(updated::add);
                        return next.apply(new ReasoningInput(updated, input.tools(), input.options()));
                    });
        });
    }

    public ContextUsageSnapshot getContextUsageSnapshot(List<Msg> messages) {
        int tokens = TokenCounterUtil.calculateToken(messages);
        double tokenPressure = tokens / (double) config.getTriggerTokens();
        double messagePressure = messages.size() / (double) config.getTriggerMessages();
        return new ContextUsageSnapshot(tokens, maxToken, config.getTriggerTokens(), messages.size(),
                config.getTriggerMessages(), tokenPressure, messagePressure,
                Math.min(1, Math.max(tokenPressure, messagePressure)),
                tokenPressure >= messagePressure ? "TOKEN" : "MESSAGE");
    }

    @SuppressWarnings("unchecked")
    private void notifyLifecycle(RuntimeContext rc, CompressionLifecycleEvent event) {
        if (rc.get(LISTENER_KEY) instanceof Consumer<?> listener) {
            ((Consumer<CompressionLifecycleEvent>) listener).accept(event);
        }
    }

    public enum CompressionStatus { STARTED, FINISHED, SKIPPED, FAILED }
    public record CompressionLifecycleEvent(CompressionStatus status, ContextUsageSnapshot before,
            ContextUsageSnapshot after, boolean compressed) {}
    public record ContextUsageSnapshot(int usedTokens, long totalTokens, long tokenThreshold,
            int messageCount, int messageThreshold, double tokenPressure, double messagePressure,
            double compressionPressure, String triggerReason) {}
}
