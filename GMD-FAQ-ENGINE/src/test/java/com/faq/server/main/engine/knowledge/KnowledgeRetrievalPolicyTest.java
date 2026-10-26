package com.gmd.faq.server.main.engine.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRetrievalPolicyTest {
    @Test void savedBudgetSurvivesSdkJsonRoundTrip() {
        var codec = io.agentscope.core.util.JsonUtils.getJsonCodec();
        var saved = new KnowledgeRetrievalBudget.Saved(2, true);
        assertEquals(saved, codec.fromJson(codec.toJson(saved), KnowledgeRetrievalBudget.Saved.class),
                "暂停后跨进程恢复必须保留已用次数和自动检索标记");
    }

    @Test void capsParallelCallsAndKeepsBudgetOnResumeButResetsForNextQuestion() throws Exception {
        var requests = new AtomicInteger();
        var settings = KnowledgeRetrievalSettings.parse(new ObjectMapper().readTree("{\"maxCallsPerRound\":1}"));
        var config = RetrieveConfig.builder().build();
        var retrieval = new KnowledgeRetrievalTool(List.of(knowledge((q, c) -> {
            requests.incrementAndGet(); return Mono.just(List.of());
        })), config, settings);
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(retrieval);
        var modelCalls = new AtomicInteger();
        Model model = model((messages, schemas) -> {
            if (modelCalls.incrementAndGet() == 1) return List.of(call("a"), call("b"));
            assertTrue(schemas.stream().noneMatch(schema -> KnowledgeRetrievalTool.NAME.equals(schema.getName())));
            return List.of(TextBlock.builder().text("依据现有证据回答").build());
        });
        var store = new InMemoryAgentStateStore();
        try (var agent = ReActAgent.builder().name("policy").model(model).toolkit(toolkit).stateStore(store)
                .middleware(new KnowledgeRetrievalMiddleware(retrieval, config, settings, false)).maxIters(3).build()) {
            agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("设备").build()), context()).block(Duration.ofSeconds(10));
            assertEquals(1, requests.get(), "同批多个检索工具不能绕过次数限制");
            var resumed = context();
            KnowledgeRetrievalBudget.initialize(agent, resumed, false);
            assertEquals(ToolResultState.ERROR, retrieval.retrieveKnowledge("继续查", agent, resumed).block().getState());
            assertEquals(1, requests.get());
            KnowledgeRetrievalBudget.initialize(agent, resumed, true);
            assertEquals(ToolResultState.SUCCESS, retrieval.retrieveKnowledge("新问题", agent, resumed).block().getState());
            assertEquals(2, requests.get());
        }
    }

    @Test void automaticRetrievalAddsOnlyConfiguredPreviousQuestionsWithoutRewriteModel() throws Exception {
        var queries = new CopyOnWriteArrayList<String>();
        var settings = KnowledgeRetrievalSettings.parse(new ObjectMapper().readTree("{\"ragMode\":\"GENERIC\",\"historyTurns\":1,\"maxCallsPerRound\":1}"));
        var config = RetrieveConfig.builder().build();
        var retrieval = new KnowledgeRetrievalTool(List.of(knowledge((q, c) -> {
            queries.add(q); return Mono.just(List.of());
        })), config, settings);
        var calls = new AtomicInteger();
        Model model = model((messages, schemas) -> {
            calls.incrementAndGet();
            assertTrue(messages.stream().anyMatch(message -> message.getRole() == MsgRole.SYSTEM
                    && message.getTextContent().contains("证据缺失")));
            return List.of(TextBlock.builder().text("检索证据不足").build());
        });
        try (var agent = ReActAgent.builder().name("automatic").model(model)
                .middleware(new KnowledgeRetrievalMiddleware(retrieval, config, settings, true)).build()) {
            agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("GMD照明设备").build()), context()).block(Duration.ofSeconds(10));
            agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("它的电压呢").build()), context()).block(Duration.ofSeconds(10));
            assertEquals(2, calls.get()); assertEquals(2, queries.size());
            assertTrue(queries.getLast().contains("GMD照明设备"));
            assertTrue(queries.getLast().endsWith("当前问题：\n它的电压呢"));
            agent.call(List.of(), context()).block(Duration.ofSeconds(10));
            assertEquals(2, queries.size(), "空输入续跑不检索历史用户问题");
        }
    }

    private static RuntimeContext context() { return RuntimeContext.builder().userId("tenant_user").sessionId("session").build(); }
    private static ToolUseBlock call(String id) {
        return ToolUseBlock.builder().id(id).name(KnowledgeRetrievalTool.NAME).input(Map.of("query", "设备"))
                .content("{\"query\":\"设备\"}").build();
    }
    private interface Retrieve { Mono<List<Document>> apply(String query, RetrieveConfig config); }
    private static KnowledgeWrapper knowledge(Retrieve retrieval) {
        return KnowledgeWrapper.builder().knowledge(new Knowledge() {
            public Mono<Void> addDocuments(List<Document> documents) { return Mono.empty(); }
            public Mono<List<Document>> retrieve(String query, RetrieveConfig config) { return retrieval.apply(query, config); }
        }).build();
    }
    private interface Generate { List<ContentBlock> apply(List<Msg> messages, List<ToolSchema> schemas); }
    private static Model model(Generate generate) {
        return new Model() {
            public String getModelName() { return "test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(generate.apply(messages, tools)).build());
            }
        };
    }
}
