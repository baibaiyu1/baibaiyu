package com.gmd.faq.server.main.engine.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.agent.AgentSessionPolicy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ObservableAutoContextMemoryTest {
    @Test void compressesHistoryWithoutSplittingToolPairOrPersistingTaskReminder() throws Exception {
        RecordingModel model = new RecordingModel();
        ObservableAutoContextMemory memory = middleware(model, 6, 2);
        var store = new InMemoryAgentStateStore();
        AgentState state = AgentState.builder().userId("u").sessionId("s").context(history()).build();
        state.getTasksContext().tasksMutable().add(Task.builder().subject("当前任务").description("").build());
        store.save("u", "s", "agent_state", state);
        var permission = PermissionContextState.builder().build();
        ReActAgent agent = ReActAgent.builder().name("test").model(model).stateStore(store)
                .middleware(new AgentSessionPolicy(true, permission)).middleware(memory).build();
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();
        List<ObservableAutoContextMemory.CompressionLifecycleEvent> lifecycle = new ArrayList<>();
        rc.put(ObservableAutoContextMemory.LISTENER_KEY,
                (java.util.function.Consumer<ObservableAutoContextMemory.CompressionLifecycleEvent>) lifecycle::add);
        agent.call(List.of(), rc).block(Duration.ofSeconds(10));
        List<Msg> persisted = store.get("u", "s", "agent_state", AgentState.class).orElseThrow().getContext();
        assertEquals(2, model.inputs.size());
        assertTrue(persisted.size() < history().size() + 1);
        assertTrue(persisted.stream().noneMatch(msg -> Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))));
        var uses = persisted.stream().flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()).map(ToolUseBlock::getId).toList();
        var results = persisted.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream()).map(ToolResultBlock::getId).toList();
        assertEquals(uses, results);
        assertTrue(model.inputs.getLast().stream().anyMatch(msg -> Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))));
        assertEquals(List.of(ObservableAutoContextMemory.CompressionStatus.STARTED, ObservableAutoContextMemory.CompressionStatus.FINISHED),
                lifecycle.stream().map(ObservableAutoContextMemory.CompressionLifecycleEvent::status).toList());
    }

    @Test void skipsWhenThereIsNoSafeHistoryToSummarize() throws Exception {
        RecordingModel model = new RecordingModel();
        var store = new InMemoryAgentStateStore();
        store.save("u", "s", "agent_state", AgentState.builder().userId("u").sessionId("s").context(history()).build());
        ReActAgent agent = ReActAgent.builder().name("test").model(model).stateStore(store)
                .middleware(middleware(model, 2, 30)).build();
        agent.call(List.of(), RuntimeContext.builder().userId("u").sessionId("s").build()).block(Duration.ofSeconds(10));
        assertEquals(1, model.inputs.size());
        assertEquals(history().size() + 1, store.get("u", "s", "agent_state", AgentState.class).orElseThrow().getContext().size());
    }

    @Test void separateSummaryModelNeverReplacesMainModelAndPreservesToolPairs() throws Exception {
        RecordingModel main = new RecordingModel();
        RecordingModel summary = new RecordingModel();
        var store = new InMemoryAgentStateStore();
        store.save("u", "s", "agent_state", AgentState.builder().userId("u").sessionId("s").context(history()).build());
        try (var agent = ReActAgent.builder().name("separate-summary").model(main).stateStore(store)
                .middleware(middleware(summary,6,2)).build()) {
            agent.call(List.of(),RuntimeContext.builder().userId("u").sessionId("s").build()).block(Duration.ofSeconds(10));
        }
        assertEquals(1,summary.inputs.size());assertEquals(1,main.inputs.size());
        assertTrue(main.inputs.getFirst().size() < history().size());
        var persisted = store.get("u","s","agent_state",AgentState.class).orElseThrow().getContext();
        assertEquals(persisted.stream().flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream()).map(ToolUseBlock::getId).toList(),
                persisted.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).map(ToolResultBlock::getId).toList());
    }

    @Test void summaryFailurePreservesHistoryAndReportsFailureOnlyOnce() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Model model = new Model() {
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                return calls.incrementAndGet() == 1 ? Flux.error(new IllegalStateException("summary unavailable"))
                        : Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("正常回复").build())).build());
            }
            public String getModelName() { return "test"; }
        };
        var store = new InMemoryAgentStateStore();
        store.save("u", "s", "agent_state", AgentState.builder().userId("u").sessionId("s").context(history()).build());
        ReActAgent agent = ReActAgent.builder().name("test").model(model).stateStore(store)
                .middleware(middleware(model, 6, 2)).build();
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();
        List<ObservableAutoContextMemory.CompressionLifecycleEvent> lifecycle = new ArrayList<>();
        rc.put(ObservableAutoContextMemory.LISTENER_KEY,
                (java.util.function.Consumer<ObservableAutoContextMemory.CompressionLifecycleEvent>) lifecycle::add);
        agent.call(List.of(), rc).block(Duration.ofSeconds(10));
        assertEquals(2, calls.get());
        assertEquals(history().size() + 1, store.get("u", "s", "agent_state", AgentState.class).orElseThrow().getContext().size());
        assertEquals(List.of(ObservableAutoContextMemory.CompressionStatus.STARTED, ObservableAutoContextMemory.CompressionStatus.FAILED),
                lifecycle.stream().map(ObservableAutoContextMemory.CompressionLifecycleEvent::status).toList());
    }

    private static ObservableAutoContextMemory middleware(Model model, int trigger, int keep) throws Exception {
        return new ObservableAutoContextMemory(new ObjectMapper().readTree(
                "{\"maxToken\":100000,\"msgThreshold\":" + trigger + ",\"lastKeep\":" + keep + "}"), model);
    }

    private static List<Msg> history() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            msgs.add(Msg.builder().role(MsgRole.USER).textContent("历史问题" + "内容".repeat(500)).build());
            msgs.add(Msg.builder().role(MsgRole.ASSISTANT).textContent("历史回答" + "记录".repeat(500)).build());
        }
        msgs.add(Msg.builder().role(MsgRole.ASSISTANT).content(new ToolUseBlock("pair", "read", Map.of())).build());
        msgs.add(Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("pair", "read", TextBlock.builder().text("工具结果").build())).build());
        return msgs;
    }

    private static class RecordingModel implements Model {
        final List<List<Msg>> inputs = new ArrayList<>();
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(msgs));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("简短摘要").build())).build());
        }
        public String getModelName() { return "test"; }
    }
}
