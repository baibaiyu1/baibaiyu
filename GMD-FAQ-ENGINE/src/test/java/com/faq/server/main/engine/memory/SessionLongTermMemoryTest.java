package com.gmd.faq.server.main.engine.memory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SessionLongTermMemoryTest {
    @Test void recordsExplicitUserSessionAndKeepsRetrievedMemoryOutOfHistory() throws Exception {
        var recorded = new CompletableFuture<List<Msg>>();
        LongTermMemory memory = mock(LongTermMemory.class);
        when(memory.retrieve(any())).thenReturn(Mono.just("当前用户的偏好"));
        when(memory.record(anyList())).thenAnswer(invocation -> Mono.fromRunnable(() -> recorded.complete(List.copyOf(invocation.getArgument(0)))));
        Model model = new Model() {
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                assertTrue(msgs.stream().anyMatch(msg -> msg.getRole() == MsgRole.SYSTEM && msg.getTextContent().contains("当前用户的偏好")));
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("本轮回复").build())).build());
            }
            public String getModelName() { return "test"; }
        };
        var store = new InMemoryAgentStateStore();
        ReActAgent agent = ReActAgent.builder().name("test").model(model).stateStore(store)
                .middleware(new SessionLongTermMemory(memory)).build();
        agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("本轮问题").build()),
                RuntimeContext.builder().userId("tenant_1_user_2").sessionId("session").build()).block(Duration.ofSeconds(10));
        List<Msg> snapshot = recorded.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("本轮问题", "本轮回复"), snapshot.stream().map(Msg::getTextContent).toList());
        assertTrue(agent.getAgentState(null, agent.getDefaultSessionId()).getContext().isEmpty());
    }
}
