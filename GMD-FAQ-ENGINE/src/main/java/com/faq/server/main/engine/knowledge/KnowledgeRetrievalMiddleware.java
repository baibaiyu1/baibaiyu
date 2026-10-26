package com.gmd.faq.server.main.engine.knowledge;

import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 自动检索在首次推理前完成，复用工具图片、日志和展示链路，不增加决策模型调用。 */
public final class KnowledgeRetrievalMiddleware implements MiddlewareBase {
    public static final String EVENT_NAME = "KNOWLEDGE_RETRIEVAL";
    private static final String QUERY = "gmd.knowledge.query";
    private final KnowledgeRetrievalTool tool;
    private final KnowledgeRetrievalSettings settings;
    private final boolean automatic;

    public KnowledgeRetrievalMiddleware(KnowledgeRetrievalTool tool, RetrieveConfig config) {
        this(tool, config, KnowledgeRetrievalSettings.parse(null), true);
    }

    public KnowledgeRetrievalMiddleware(KnowledgeRetrievalTool tool, RetrieveConfig config,
            KnowledgeRetrievalSettings settings, boolean automatic) {
        this.tool = tool;
        this.settings = settings;
        this.automatic = automatic;
    }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            String query = "";
            if (input.msgs() != null) for (Msg msg : input.msgs()) {
                if (msg.getRole() == MsgRole.USER && !"retrieved_knowledge".equals(msg.getName())
                        && !msg.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS)) query = msg.getTextContent();
            }
            context.put(QUERY, query);
            KnowledgeRetrievalBudget.initialize(agent, context, query != null && !query.isBlank());
            return next.apply(input);
        });
    }

    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.just((prompt == null ? "" : prompt) + "\n\n知识库回答要求：" + tool.evidenceInstruction()
                + "本轮最多检索 " + (automatic ? 1 : settings.maxCallsPerRound()) + " 次；使用已有证据后直接回答，证据不足时说明限制。");
    }

    @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            String query = context.get(QUERY);
            KnowledgeRetrievalBudget budget = KnowledgeRetrievalBudget.of(agent, context);
            if (!automatic) {
                var tools = input.tools() == null ? null : input.tools().stream()
                        .filter(schema -> !KnowledgeRetrievalTool.NAME.equals(schema.getName())
                                || budget.hasRemaining(settings.maxCallsPerRound())).toList();
                return next.apply(new ReasoningInput(input.messages(), tools, input.options()));
            }
            if (budget.automaticRetrieved() || query == null || query.isBlank()) return next.apply(input);
            budget.automaticRetrieved(agent, context);
            String retrievalQuery = automaticQuery(query, RuntimeContext.resolveAgentState(context, agent).getContext());
            String id = "knowledge-" + UUID.randomUUID();
            return Flux.<AgentEvent>just(retrievalEvent(id, "STARTED")).concatWith(Mono.defer(() ->
                    tool.retrieveKnowledge(retrievalQuery, agent, context)).flatMapMany(result -> {
                ToolResultBlock identified = result.withIdAndName(id, KnowledgeRetrievalTool.NAME);
                var call = ToolUseBlock.builder().id(id).name(KnowledgeRetrievalTool.NAME).input(Map.of("query", query)).build();
                var event = new PostActingEvent(agent, agent.getToolkit(), call, identified);
                event.setToolResultMsg(Msg.builder().role(MsgRole.TOOL).content(identified).build());
                Mono<PostActingEvent> normalized = Mono.just(event);
                if (agent instanceof AgentBase base) for (var hook : base.getSortedHooks()) {
                    normalized = normalized.flatMap(hook::onEvent);
                }
                return normalized.flatMapMany(completed -> {
                    ToolResultBlock enriched = completed.getToolResult();
                    Msg evidence = Msg.builder().role(MsgRole.USER).name("retrieved_knowledge")
                            .content(enriched.getOutput()).build();
                    var messages = new ArrayList<>(input.messages());
                    messages.add(evidence);
                    RuntimeContext.resolveAgentState(context, agent).contextMutable().add(evidence);
                    ToolResultPresentation presentation = ToolResultPresentation.from(enriched);
                    Flux<AgentEvent> display = presentation.hasAttachments()
                            ? Flux.just(new CustomEvent(ToolResultPresentation.EVENT_NAME, Map.of(
                                    "toolCallId", id, "toolName", KnowledgeRetrievalTool.NAME,
                                    "text", presentation.text(), "images", presentation.images(),
                                    "artifacts", presentation.artifacts(), "citations", presentation.citations())))
                            : Flux.empty();
                    return display.concatWithValues(retrievalEvent(id, "FINISHED"))
                            .concatWith(next.apply(new ReasoningInput(messages, input.tools(), input.options())));
                });
            }));
        });
    }

    private CustomEvent retrievalEvent(String id, String status) {
        return new CustomEvent(EVENT_NAME, Map.of("toolCallId", id, "status", status));
    }

    private String automaticQuery(String query, List<Msg> history) {
        if (settings.historyTurns() == 0) return query;
        var questions = new ArrayList<>(history.stream().filter(message -> message.getRole() == MsgRole.USER
                && !"retrieved_knowledge".equals(message.getName()) && !message.getTextContent().isBlank())
                .map(Msg::getTextContent).toList());
        if (!questions.isEmpty() && query.equals(questions.getLast())) questions.removeLast();
        if (questions.isEmpty()) return query;
        StringBuilder result = new StringBuilder("之前的问题（只用于理解当前提问）：\n");
        for (String previous : questions.subList(Math.max(0, questions.size() - settings.historyTurns()), questions.size())) {
            result.append(previous, 0, Math.min(previous.length(), 1000)).append('\n');
        }
        return result.append("当前问题：\n").append(query).toString();
    }
}
