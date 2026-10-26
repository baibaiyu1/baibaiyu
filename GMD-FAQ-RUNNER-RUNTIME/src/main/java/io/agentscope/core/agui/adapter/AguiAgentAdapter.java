/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import com.gmd.faq.server.main.common.subagent.SubAgentTraceEvent;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.knowledge.KnowledgeRetrievalMiddleware;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.engine.memory.ObservableAutoContextMemory;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Adapter that bridges AgentScope agents to the AG-UI protocol.
 *
 * <p>This adapter converts AG-UI protocol inputs to AgentScope messages,
 * invokes the agent, and converts the streaming events back to AG-UI events.
 *
 * <p><b>Event Mapping:</b>
 * <ul>
 *   <li>AgentScope REASONING events → AG-UI TEXT_MESSAGE_* events (for TextBlock)</li>
 *   <li>AgentScope REASONING events → AG-UI REASONING_* events (for ThinkingBlock, when enabled)</li>
 *   <li>AgentScope TOOL_RESULT events → AG-UI TOOL_CALL_END events</li>
 *   <li>ToolUseBlock content → AG-UI TOOL_CALL_START events</li>
 * </ul>
 *
 * <p><b>Reasoning Support:</b>
 * <ul>
 *   <li>ThinkingBlock content is converted to REASONING_* events according to AG-UI Reasoning draft</li>
 *   <li>Reasoning output is disabled by default (enableReasoning=false) for backward compatibility</li>
 *   <li>Set enableReasoning=true in AguiAdapterConfig to enable reasoning events</li>
 * </ul>
 */
public class AguiAgentAdapter {
    /** 本轮是否因 HITL 确认而暂停（REASONING_STOP_REQUESTED）。供 processor 决定是否无条件保存暂停态。 */
    @Getter
    private volatile boolean suspended = false;

    private RuntimeContext runtimeContext;
    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;

    /**
     * Creates a new AguiAgentAdapter.
     *
     * @param agent The agent to adapt
     * @param config The adapter configuration
     */
    public AguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
    }

    /**
     * Run the agent with AG-UI protocol input.
     *
     * <p>This method converts the input messages, invokes the agent's streaming API,
     * and emits AG-UI protocol events.
     *
     * @param input The AG-UI run input
     * @return A Flux of AG-UI events
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        // Convert AG-UI messages to AgentScope messages
        List<Msg> msgs = messageConverter.toMsgList(input.getMessages());
        return runWithMessages(msgs, input.getThreadId(), input.getRunId());
    }

    /**
     * HITL resume：直接以 AgentScope 原生 Msg 列表继续执行（绕过 AG-UI 消息转换），
     * 复用同一套事件转换与 SSE 输出。
     *
     * <p>空列表 = 让 agent 继续执行 pending 工具（全允许）；
     * 传入含 ToolResultBlock 的 TOOL 角色消息 = 喂入工具结果（如「用户已拒绝执行」）后继续。
     */
    public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext context) {
        return runWithMessages(messageConverter.toMsgList(input.getMessages()), input.getThreadId(), input.getRunId(), context);
    }

    public Flux<AguiEvent> runWithMessages(List<Msg> msgs, String threadId, String runId) {
        return runWithMessages(msgs, threadId, runId,
                RuntimeContext.builder().sessionId(threadId).build());
    }

    public Flux<AguiEvent> runWithMessages(List<Msg> msgs, String threadId, String runId, RuntimeContext context) {
        runtimeContext = context;
        EventConversionState state = new EventConversionState(threadId, runId);
        Sinks.Many<AguiEvent> lifecycleEvents = Sinks.many().unicast().onBackpressureBuffer();
        configureCompressionLifecycle(state, lifecycleEvents);
        if (agent instanceof ReActAgent react) {
            AguiStreamContext streamContext = new AguiStreamContext(threadId, runId, config);
            AgentEventConverterRegistry converters = new AgentEventConverterRegistry(
                    config.getEventConverters(), config.getEventEnrichers(), config.isEmitSubagentEventsAsNative());
            Map<String, List<ContentBlock>> outputs = new HashMap<>();
            Map<String, CompletedToolResult> completedResults = new LinkedHashMap<>();
            Map<String, List<ToolResultPresentation.Artifact>> producedArtifacts = new ConcurrentHashMap<>();
            Flux<AguiEvent> events = react.streamEvents(msgs, runtimeContext)
                    .contextWrite(reactorContext -> reactorContext.put(ToolResultPresentation.ARTIFACTS_LISTENER_CONTEXT_KEY,
                            (BiConsumer<Agent, ToolResultBlock>) (sourceAgent, result) -> {
                                if (sourceAgent == agent) {
                                    producedArtifacts.put(result.getId(), ToolResultPresentation.from(result).artifacts());
                                }
                            }))
                    .concatMapIterable(event -> {
                        List<AguiEvent> converted = new ArrayList<>();
                        if (Boolean.FALSE.equals(com.gmd.faq.server.main.common.util.AgentMetadataStore.get(agent.getAgentId(), "showThinking"))
                                && (event instanceof ThinkingBlockStartEvent || event instanceof ThinkingBlockDeltaEvent
                                || event instanceof ThinkingBlockEndEvent)) return List.of();
                        if (event instanceof io.agentscope.core.event.CustomEvent custom && java.util.Set.of("MODEL_USAGE", "MODEL_RETRY", "MODEL_SWITCH", ToolResultPresentation.EVENT_NAME, KnowledgeRetrievalMiddleware.EVENT_NAME).contains(custom.getName())) {
                            return List.of(new AguiEvent.Custom(threadId,runId,custom.getName(),custom.getValue()));
                        }
                        // 2.0 的 ToolResultEnd 在 PostActing Hook 前发出；下一阶段才读取归一化结果。
                        if (event.getType() == AgentEventType.MODEL_CALL_START
                                || event.getType() == AgentEventType.AGENT_RESULT
                                || event.getType() == AgentEventType.AGENT_END) {
                            converted.addAll(flushToolResults(completedResults, producedArtifacts, threadId, runId));
                        }
                        if (event.getMetadata() != null && event.getMetadata().containsKey(SubAgentTraceEvent.METADATA_KEY)) {
                            return List.of(new AguiEvent.Custom(threadId, runId, SubAgentTraceEvent.CUSTOM_EVENT_NAME,
                                    event.getMetadata().get(SubAgentTraceEvent.METADATA_KEY)));
                        }
                        if (event instanceof ToolResultTextDeltaEvent delta) {
                            outputs.computeIfAbsent(delta.getToolCallId(), key -> new ArrayList<>())
                                    .add(TextBlock.builder().text(delta.getDelta()).build());
                        } else if (event instanceof ToolResultDataDeltaEvent delta) {
                            outputs.computeIfAbsent(delta.getToolCallId(), key -> new ArrayList<>()).add(delta.getData());
                        } else if (event instanceof ToolResultEndEvent ended) {
                            if (ended.getMetadata() != null && ended.getMetadata().containsKey(SubAgentTraceEvent.FINAL_RESULT_METADATA_KEY)) {
                                outputs.remove(ended.getToolCallId());
                                return converters.convert(event, streamContext).stream()
                                        .filter(value -> !(value instanceof AguiEvent.ToolCallResult)).toList();
                            }
                            ToolResultBlock result = new ToolResultBlock(ended.getToolCallId(), ended.getToolCallName(),
                                    outputs.getOrDefault(ended.getToolCallId(), List.of()), ended.getMetadata()).withState(ended.getState());
                            if (ended.getState() != io.agentscope.core.message.ToolResultState.RUNNING) {
                                completedResults.put(ended.getToolCallId(), new CompletedToolResult(ended.getReplyId(), result));
                            }
                            outputs.remove(ended.getToolCallId());
                        } else if (event instanceof RequireUserConfirmEvent confirmation) {
                            suspended = true;
                            List<Map<String, Object>> pending = confirmation.getToolCalls().stream()
                                    .map(tool -> Map.<String, Object>of("toolUseId", tool.getId(), "name", tool.getName(),
                                            "input", tool.getInput())).toList();
                            converted.add(new AguiEvent.Custom(threadId, runId, "TOOL_CONFIRM_REQUIRED", Map.of("pending", pending)));
                        }
                        converted.addAll(converters.convert(event, streamContext).stream()
                                .filter(value -> !(value instanceof AguiEvent.ToolCallResult)).toList());
                        if (event.getType() == AgentEventType.AGENT_START) {
                            converted.addAll(buildInitialContextUsageEvent(state));
                        } else if (event.getType() == AgentEventType.MODEL_CALL_END
                                || event.getType() == AgentEventType.TOOL_RESULT_END
                                || event.getType() == AgentEventType.AGENT_RESULT) {
                            converted.addAll(buildContextUsageEvents(state, EventType.AGENT_RESULT));
                        }
                        return converted;
                    }).doFinally(signal -> lifecycleEvents.tryEmitComplete());
            return Flux.concat(Flux.merge(lifecycleEvents.asFlux(), events),
                            Flux.defer(() -> Flux.fromIterable(streamContext.finishPendingEvents())))
                    .onErrorResume(error -> Flux.concat(
                            Flux.fromIterable(flushToolResults(completedResults, producedArtifacts, threadId, runId)),
                            Flux.fromIterable(streamContext.finishPendingEvents()),
                            Flux.just(new AguiEvent.RunError(threadId, runId, error.getMessage(), "AGENT_ERROR"),
                                    new AguiEvent.RunFinished(threadId, runId))))
                    .doFinally(signal -> {
                        producedArtifacts.clear();
                        clearCompressionLifecycle();
                    });
        }
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.ALL).incremental(true).build();
        Flux<AguiEvent> events = agent.stream(msgs, options, runtimeContext)
                .concatMapIterable(event -> convertEvent(event, state))
                .doFinally(signal -> lifecycleEvents.tryEmitComplete());
        return Flux.concat(Flux.just(new AguiEvent.RunStarted(threadId, runId)),
                Flux.merge(lifecycleEvents.asFlux(), events), Flux.defer(() -> finishRun(state)))
                .doFinally(signal -> clearCompressionLifecycle());
    }

    private ObservableAutoContextMemory compressionMemory() {
        if (agent instanceof ReActAgent react) {
            return react.getMiddlewares().stream().filter(ObservableAutoContextMemory.class::isInstance)
                    .map(ObservableAutoContextMemory.class::cast).findFirst().orElse(null);
        }
        return null;
    }

    private record CompletedToolResult(String replyId, ToolResultBlock result) {}

    private List<AguiEvent> flushToolResults(Map<String, CompletedToolResult> completed,
            Map<String, List<ToolResultPresentation.Artifact>> producedArtifacts, String threadId, String runId) {
        if (completed.isEmpty()) return List.of();
        Map<String, ToolResultBlock> normalized = new HashMap<>();
        contextMessages().forEach(message -> message.getContentBlocks(ToolResultBlock.class)
                .forEach(result -> normalized.put(result.getId(), result)));
        List<AguiEvent> events = new ArrayList<>();
        completed.forEach((id, value) -> {
            var tool = agent instanceof ReActAgent react ? react.getToolkit().getTool(value.result().getName()) : null;
            // 直接返回工具已在返回边界归一化，SDK 上下文中的结果只是占位，展示保留真实输出。
            ToolResultBlock result = tool != null && tool.isReturnDirect()
                    ? value.result() : normalized.getOrDefault(id, value.result());
            ToolResultPresentation presentation = ToolResultPresentation.from(result);
            List<ToolResultPresentation.Artifact> artifacts = producedArtifacts.remove(id);
            if (artifacts != null) {
                presentation = new ToolResultPresentation(presentation.text(), presentation.images(), artifacts, presentation.citations());
            }
            if (presentation.hasAttachments()) {
                events.add(new AguiEvent.Custom(threadId, runId, ToolResultPresentation.EVENT_NAME,
                        Map.of("toolCallId", id, "toolName", result.getName(),
                                "text", presentation.text(), "images", presentation.images(), "artifacts", presentation.artifacts(),
                                "citations", presentation.citations())));
            }
            events.add(new AguiEvent.ToolCallResult(threadId, runId, id,
                    presentation.text(), "tool", value.replyId() + ":" + id));
        });
        completed.clear();
        return events;
    }

    private List<Msg> contextMessages() {
        if (runtimeContext != null && runtimeContext.getAgentState() != null) {
            return runtimeContext.getAgentState().getContext();
        }
        return agent instanceof ReActAgent react && runtimeContext != null
                ? react.getAgentState(runtimeContext).getContext() : List.of();
    }

    /**
     * Convert an AgentScope event to AG-UI events.
     *
     * @param event The AgentScope event
     * @param state The conversion state
     * @return List of AG-UI events
     */
    private List<AguiEvent> convertEvent(Event event, EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        EventType type = event.getType();

        // HITL：检测到「推理后暂停」即标记本轮挂起，供 AguiRequestProcessor 无条件保存暂停态
        if (msg != null && msg.getGenerateReason() == GenerateReason.REASONING_STOP_REQUESTED) {
            this.suspended = true;
        }

        if (type == EventType.REASONING) {
            // Handle reasoning events - convert to text messages and tool calls
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isEmpty()) {
                        // 平台如果当前已有活跃的文本消息，复用其 messageId，避免产生多个 TEXT_MESSAGE_START
                        String messageId;
                        if (state.hasActiveTextMessage()) {
                            messageId = state.getCurrentTextMessageId();
                        } else {
                            messageId = msg.getId();
                        }

                        // Start message if not started
                        if (!state.hasStartedMessage(messageId)) {
                            events.add(
                                    new AguiEvent.ReasoningMessageEnd(
                                            state.threadId, state.runId, messageId));
                            events.add(
                                    new AguiEvent.TextMessageStart(
                                            state.threadId, state.runId, messageId, "assistant"));
                            state.startMessage(messageId);
                        }

                        if (!event.isLast()) {
                            // In incremental mode, text is already the delta
                            events.add(
                                    new AguiEvent.TextMessageContent(
                                            state.threadId, state.runId, messageId, text));
                        } else {
                            // End message if this is the last event
                            if (!state.hasEndedMessage(messageId)) {
                                events.add(
                                        new AguiEvent.TextMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endMessage(messageId);
                            }
                        }
                    }
                } else if (block instanceof ThinkingBlock thinkingBlock) {
                    // Handle thinking blocks - convert to REASONING_* events (only if enabled)
                    // According to AG-UI Reasoning draft: https://docs.ag-ui.com/drafts/reasoning
                    if (config.isEnableReasoning() && !Boolean.FALSE.equals(com.gmd.faq.server.main.common.util.AgentMetadataStore
                            .get(agent.getAgentId(), "showThinking"))) {
                        String thinking = thinkingBlock.getThinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            // 平台如果当前已有活跃的文本消息，复用其 messageId，避免产生多个 TEXT_MESSAGE_START
                            String messageId;
                            if (state.hasActiveTextMessage()) {
                                messageId = state.getCurrentTextMessageId();
                            } else {
                                messageId = msg.getId();
                            }

                            // Start reasoning message if not started
                            boolean reasoningStarted = state.hasStartedReasoningMessage(messageId);
                            if (!reasoningStarted) {
                                events.add(
                                        new AguiEvent.ReasoningMessageStart(
                                                state.threadId,
                                                state.runId,
                                                messageId,
                                                "assistant"));
                                state.startReasoningMessage(messageId);
                            }

                            if (!event.isLast()) {
                                // In incremental mode, thinking is already the delta
                                events.add(
                                        new AguiEvent.ReasoningMessageContent(
                                                state.threadId, state.runId, messageId, thinking));
                            } else {
                                // End reasoning message if this is the last event
                                if (!reasoningStarted) events.add(new AguiEvent.ReasoningMessageContent(
                                        state.threadId, state.runId, messageId, thinking));
                                events.add(
                                        new AguiEvent.ReasoningMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endReasoningMessage(messageId);
                            }
                        }
                    }
                    // If reasoning is disabled, ThinkingBlock content is ignored (backward
                    // compatibility)
                } else if (block instanceof ToolUseBlock toolUse) {
                    // End any active text message before starting tool call
                    if (state.hasActiveTextMessage()) {
                        String activeMessageId = state.getCurrentTextMessageId();
                        events.add(
                                new AguiEvent.TextMessageEnd(
                                        state.threadId, state.runId, activeMessageId));
                        events.add(
                                new AguiEvent.ReasoningMessageEnd(
                                        state.threadId, state.runId, activeMessageId));
                        state.endMessage(activeMessageId);
                    }

                    // Emit tool call start
                    String toolCallId = toolUse.getId();
                    if (toolCallId == null) {
                        toolCallId = UUID.randomUUID().toString();
                    }

                    if (!state.hasStartedToolCall(toolCallId)) {
                        events.add(
                                new AguiEvent.ToolCallStart(
                                        state.threadId,
                                        state.runId,
                                        toolCallId,
                                        toolUse.getName()));
                        state.startToolCall(toolCallId);
                    }

                    // Emit tool call args if enabled
                    if (config.isEmitToolCallArgs() && !event.isLast()) {
                        String args = toolUse.getContent();
                        if (args != null && !args.isEmpty()) {
                            events.add(
                                    new AguiEvent.ToolCallArgs(
                                            state.threadId, state.runId, toolCallId, args));
                        }
                    }
                }
            }
        } else if (type == EventType.TOOL_RESULT) {
            boolean subAgentTraceEvent = false;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult
                        && toolResult.getMetadata().containsKey(SubAgentTraceEvent.METADATA_KEY)) {
                    Object trace = toolResult.getMetadata().get(SubAgentTraceEvent.METADATA_KEY);
                    events.add(new AguiEvent.Custom(
                            state.threadId,
                            state.runId,
                            SubAgentTraceEvent.CUSTOM_EVENT_NAME,
                            trace));
                    subAgentTraceEvent = true;
                }
            }

            if (subAgentTraceEvent) {
                // Intermediate sub-agent chunks are a separate custom protocol. They must never
                // enter the parent agent's text/tool conversion state.
                return events;
            }

            if (event.isLast()) {
                // Handle tool results
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock toolResult) {
                        if (toolResult.getMetadata().containsKey(SubAgentTraceEvent.FINAL_RESULT_METADATA_KEY)) {
                            // The dedicated sub-agent card replaces the generic parent tool card.
                            continue;
                        }
                        String toolCallId = toolResult.getId();
                        String result = extractToolResultText(toolResult);

                        boolean hasStarted = state.hasStartedToolCall(toolCallId);
                        if (!hasStarted) {
                            events.add(
                                    new AguiEvent.ToolCallStart(
                                            state.threadId, state.runId, toolCallId, "unknown"));
                            state.startToolCall(toolCallId);
                        }

                        // Ensure ToolCallEnd is emitted to close arguments phase
                        events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));

                        ToolResultPresentation presentation = ToolResultPresentation.from(toolResult);
                        if (presentation.hasAttachments()) {
                            Map<String, Object> value = new HashMap<>();
                            value.put("toolCallId", toolCallId);
                            value.put("toolName", toolResult.getName());
                            value.put("text", presentation.text());
                            value.put("images", presentation.images());
                            value.put("artifacts", presentation.artifacts());
                            value.put("citations", presentation.citations());
                            events.add(new AguiEvent.Custom(state.threadId, state.runId,
                                    ToolResultPresentation.EVENT_NAME, value));
                        }

                        events.add(
                                new AguiEvent.ToolCallResult(
                                        state.threadId,
                                        state.runId,
                                        toolCallId,
                                        result,
                                        "tool",
                                        msg.getId()));
                        state.endToolCall(toolCallId);
                    }
                }
            }
        }

        // 在一轮思考、工具执行或正文输出完成后推送上下文使用量。
        if (event.isLast()
                && (type == EventType.REASONING
                        || type == EventType.TOOL_RESULT
                        || type == EventType.AGENT_RESULT)) {
            events.addAll(buildContextUsageEvents(state, type));
        }

        return events;
    }

    /** 注册压缩开始监听器，使压缩模型调用前即可推送 SSE。 */
    private void configureCompressionLifecycle(
            EventConversionState state, Sinks.Many<AguiEvent> lifecycleEvents) {
        ObservableAutoContextMemory memory = compressionMemory();
        if (memory == null) {
            return;
        }
        runtimeContext.put(ObservableAutoContextMemory.LISTENER_KEY, (java.util.function.Consumer<ObservableAutoContextMemory.CompressionLifecycleEvent>) lifecycle -> {
            state.compressionStarted = lifecycle.status()
                    == ObservableAutoContextMemory.CompressionStatus.STARTED;
            ObservableAutoContextMemory.ContextUsageSnapshot snapshot =
                    lifecycle.status() == ObservableAutoContextMemory.CompressionStatus.STARTED
                            ? lifecycle.before()
                            : lifecycle.after();
            Map<String, Object> usage = contextUsagePayload(
                    snapshot, "COMPRESSION", lifecycle.compressed());
            usage.put("status", lifecycle.status().name());
            usage.put("compressed", lifecycle.compressed());
            usage.put("beforeUsedTokens", lifecycle.before().usedTokens());
            usage.put("beforeMessageCount", lifecycle.before().messageCount());
            usage.put("afterUsedTokens", lifecycle.after().usedTokens());
            usage.put("afterMessageCount", lifecycle.after().messageCount());
            if (lifecycle.status() != ObservableAutoContextMemory.CompressionStatus.STARTED) {
                state.compressionEventCount = currentCompressionEventCount();
            }
            lifecycleEvents.tryEmitNext(new AguiEvent.Custom(
                    state.threadId,
                    state.runId,
                    "CONTEXT_COMPRESSION",
                    usage));
        });
    }

    /** 清理监听器，避免一次运行的 SSE sink 被后续运行继续引用。 */
    private void clearCompressionLifecycle() {
        if (runtimeContext != null) runtimeContext.put(ObservableAutoContextMemory.LISTENER_KEY, null);
    }

    private int currentCompressionEventCount() {
        return runtimeContext != null && runtimeContext.get(ObservableAutoContextMemory.COUNT_KEY) instanceof Number count
                ? count.intValue() : 0;
    }

    /** 构建本轮运行开始时的上下文快照。 */
    private List<AguiEvent> buildInitialContextUsageEvent(EventConversionState state) {
        ObservableAutoContextMemory memory = compressionMemory();
        if (memory == null) {
            return List.of();
        }
        Map<String, Object> usage = contextUsagePayload(memory, "RUN", currentCompressionEventCount() > 0);
        return List.of(new AguiEvent.Custom(
                state.threadId,
                state.runId,
                "CONTEXT_USAGE",
                usage));
    }

    /** 构建上下文使用量事件，并在检测到压缩后补发压缩完成事件。 */
    private List<AguiEvent> buildContextUsageEvents(EventConversionState state, EventType type) {
        ObservableAutoContextMemory memory = compressionMemory();
        if (memory == null) {
            return List.of();
        }

        int compressionEvents = currentCompressionEventCount();
        List<AguiEvent> events = new ArrayList<>();

        if (compressionEvents > state.compressionEventCount) {
            // ObservableAutoContextMemory 会在压缩模型调用前推送 STARTED；非包装记忆保留兜底。
            if (!state.compressionStarted) {
                Map<String, Object> usage = contextUsagePayload(memory, "COMPRESSION", false);
                usage.put("status", "STARTED");
                events.add(new AguiEvent.Custom(
                        state.threadId,
                        state.runId,
                        "CONTEXT_COMPRESSION",
                        usage));
            }
            Map<String, Object> finishedUsage = contextUsagePayload(memory, "COMPRESSION", true);
            finishedUsage.put("status", "FINISHED");
            finishedUsage.put("compressed", true);
            events.add(new AguiEvent.Custom(
                    state.threadId,
                    state.runId,
                    "CONTEXT_COMPRESSION",
                    finishedUsage));
            state.compressionEventCount = compressionEvents;
            state.compressionStarted = false;
        }

        String phase = switch (type) {
            case REASONING -> "REASONING";
            case TOOL_RESULT -> "TOOL";
            case AGENT_RESULT -> "ANSWER";
            default -> "UNKNOWN";
        };
        Map<String, Object> usage = contextUsagePayload(memory, phase, compressionEvents > 0);
        events.add(new AguiEvent.Custom(
                state.threadId,
                state.runId,
                "CONTEXT_USAGE",
                usage));
        return events;
    }

    /** 将记忆快照转换为前端可识别的压缩压力数据。 */
    private Map<String, Object> contextUsagePayload(
            ObservableAutoContextMemory.ContextUsageSnapshot snapshot,
            String phase,
            boolean compressed) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("usedTokens", snapshot.usedTokens());
        usage.put("totalTokens", snapshot.totalTokens());
        usage.put("tokenThreshold", snapshot.tokenThreshold());
        usage.put("messageCount", snapshot.messageCount());
        usage.put("messageThreshold", snapshot.messageThreshold());
        usage.put("tokenPressure", snapshot.tokenPressure());
        usage.put("messagePressure", snapshot.messagePressure());
        usage.put("compressionPressure", snapshot.compressionPressure());
        usage.put("ratio", snapshot.compressionPressure());
        usage.put("triggerReason", snapshot.triggerReason());
        usage.put("phase", phase);
        usage.put("estimated", true);
        usage.put("compressed", compressed);
        usage.put("timestamp", System.currentTimeMillis());
        return usage;
    }

    private Map<String, Object> contextUsagePayload(
            ObservableAutoContextMemory memory, String phase, boolean compressed) {
        return contextUsagePayload(memory.getContextUsageSnapshot(contextMessages()), phase, compressed);
    }

    /** 从当前 Agent 配置中读取上下文上限，读取失败时使用 AgentScope 默认值。 */
    private long resolveContextLimit() {
        return AgentContext.getIfExists()
                .map(AgentContext::getAgentDefinition)
                .map(definition -> definition.getMemoryCompressionConfig())
                .map(config -> com.gmd.faq.server.main.common.util.JsonUtils.getLongValue(
                        config, "maxToken", 131072L))
                .orElse(131072L);
    }

    /** 从 Agent 配置读取 token 压缩阈值比例。 */
    private double resolveCompressionTokenRatio() {
        return AgentContext.getIfExists()
                .map(AgentContext::getAgentDefinition)
                .map(definition -> definition.getMemoryCompressionConfig())
                .map(config -> com.gmd.faq.server.main.common.util.JsonUtils.getDoubleValue(config, "tokenRatio", 0.75D))
                .orElse(0.75D);
    }

    /** 从 Agent 配置读取消息数量压缩阈值。 */
    private int resolveCompressionMessageThreshold() {
        return AgentContext.getIfExists()
                .map(AgentContext::getAgentDefinition)
                .map(definition -> definition.getMemoryCompressionConfig())
                .map(config -> com.gmd.faq.server.main.common.util.JsonUtils.getIntValue(config, "msgThreshold", 100))
                .orElse(100);
    }

    /**
     * Finish the run by emitting any pending end events and RUN_FINISHED.
     *
     * @param state The conversion state
     * @return Flux of final events
     */
    private Flux<AguiEvent> finishRun(EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();

        // End any messages that weren't properly ended
        for (String messageId : state.getStartedMessages()) {
            if (!state.hasEndedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // End any tool calls that weren't properly ended
        for (String toolCallId : state.getStartedToolCalls()) {
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
            }
        }

        // End any reasoning messages that weren't properly ended
        for (String messageId : state.getStartedReasoningMessages()) {
            if (!state.hasEndedReasoningMessage(messageId)) {
                events.add(
                        new AguiEvent.ReasoningMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // Emit RUN_FINISHED
        events.add(new AguiEvent.RunFinished(state.threadId, state.runId));

        return Flux.fromIterable(events);
    }

    /**
     * Extract text content from a tool result block.
     *
     * @param toolResult The tool result block
     * @return The text content, or null if not present
     */
    private String extractToolResultText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Serialize tool arguments to JSON string.
     *
     * @param input The tool input map
     * @return JSON string representation
     */
    private String serializeToolArgs(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (JsonException e) {
            return "{}";
        }
    }

    /**
     * State tracker for event conversion.
     * Uses LinkedHashSet to preserve insertion order for proper event sequencing.
     */
    private static class EventConversionState {
        final String threadId;
        final String runId;
        private final Set<String> startedMessages = new LinkedHashSet<>();
        private final Set<String> endedMessages = new LinkedHashSet<>();
        private final Set<String> startedToolCalls = new LinkedHashSet<>();
        private final Set<String> endedToolCalls = new LinkedHashSet<>();
        private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
        private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
        private String currentTextMessageId = null;
        private int compressionEventCount = 0;
        private boolean compressionStarted = false;

        EventConversionState(String threadId, String runId) {
            this.threadId = threadId;
            this.runId = runId;
        }

        boolean hasStartedMessage(String messageId) {
            return startedMessages.contains(messageId);
        }

        void startMessage(String messageId) {
            startedMessages.add(messageId);
            currentTextMessageId = messageId;
        }

        void endMessage(String messageId) {
            endedMessages.add(messageId);
            if (messageId.equals(currentTextMessageId)) {
                currentTextMessageId = null;
            }
        }

        boolean hasEndedMessage(String messageId) {
            return endedMessages.contains(messageId);
        }

        String getCurrentTextMessageId() {
            return currentTextMessageId;
        }

        boolean hasActiveTextMessage() {
            return currentTextMessageId != null && !hasEndedMessage(currentTextMessageId);
        }

        Set<String> getStartedMessages() {
            return startedMessages;
        }

        boolean hasStartedToolCall(String toolCallId) {
            return startedToolCalls.contains(toolCallId);
        }

        void startToolCall(String toolCallId) {
            startedToolCalls.add(toolCallId);
        }

        void endToolCall(String toolCallId) {
            endedToolCalls.add(toolCallId);
        }

        boolean hasEndedToolCall(String toolCallId) {
            return endedToolCalls.contains(toolCallId);
        }

        Set<String> getStartedToolCalls() {
            return startedToolCalls;
        }

        boolean hasStartedReasoningMessage(String messageId) {
            return startedReasoningMessages.contains(messageId);
        }

        void startReasoningMessage(String messageId) {
            startedReasoningMessages.add(messageId);
        }

        void endReasoningMessage(String messageId) {
            endedReasoningMessages.add(messageId);
        }

        boolean hasEndedReasoningMessage(String messageId) {
            return endedReasoningMessages.contains(messageId);
        }

        Set<String> getStartedReasoningMessages() {
            return startedReasoningMessages;
        }
    }
}
