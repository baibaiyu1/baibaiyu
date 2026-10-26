package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.engine.agent.ReasoningDisplayMiddleware;
import com.gmd.faq.server.main.engine.log.ChatLogHook;
import com.gmd.faq.server.main.engine.log.ConcurrentLogProducer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.*;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReasoningVisibilityTest {
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void displayAndHistoryRespectTheSamePolicyWithoutErasingInternalReasoning(boolean show, boolean deltas) {
        var saved = new CopyOnWriteArrayList<ChatMessage>();
        var history = new CopyOnWriteArrayList<Msg>();
        var log = new ChatLogHook();
        Hook capture = new Hook() {
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent post) history.add(post.getReasoningMessage());
                try (var logs = mockStatic(ConcurrentLogProducer.class)) {
                    logs.when(() -> ConcurrentLogProducer.pushLog(any())).thenAnswer(call -> { saved.add(call.getArgument(0)); return null; });
                    return log.onEvent(event);
                }
            }
        };
        Model model = new Model() {
            public String getModelName() { return "reasoning-fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (!deltas) return Flux.just(ChatResponse.builder().content(List.of(
                        ThinkingBlock.builder().thinking("原始思考").metadata(Map.of("openai.reasoning.text", "原始思考")).build(),
                        TextBlock.builder().text("回答").build())).build());
                return Flux.just(ChatResponse.builder().content(List.of(ThinkingBlock.builder().thinking("原始").build())).build(),
                        ChatResponse.builder().content(List.of(ThinkingBlock.builder().thinking("思考").build())).build(),
                        ChatResponse.builder().content(List.of(TextBlock.builder().text("回答").build())).build());
            }
        };
        try (var agent = ReActAgent.builder().name("reasoning").model(model).middleware(new ReasoningDisplayMiddleware(show))
                .hooks(List.of(capture)).build()) {
            AgentMetadataStore.put(agent.getAgentId(), "tenantId", 1L);
            AgentMetadataStore.put(agent.getAgentId(), "threadId", "123");
            try {
                var events = new AguiAgentAdapter(agent, AguiAdapterConfig.builder().enableReasoning(true).build())
                        .runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("测试").build()), "123", "run")
                        .collectList().block(Duration.ofSeconds(10));
                assertNotNull(events);
                assertFalse(events.stream().anyMatch(AguiEvent.RunError.class::isInstance), () -> events.toString());
                assertEquals(show ? "原始思考" : "", events.stream().filter(AguiEvent.ReasoningMessageContent.class::isInstance)
                        .map(AguiEvent.ReasoningMessageContent.class::cast).map(AguiEvent.ReasoningMessageContent::delta).reduce("", String::concat));
                assertEquals(show ? List.of("原始思考") : List.of(), saved.stream().filter(m -> "thinking".equals(m.getRole())).map(ChatMessage::getContent).toList());
                assertEquals("原始思考", history.getFirst().getFirstContentBlock(ThinkingBlock.class).getThinking());
                assertTrue(saved.stream().anyMatch(m -> "assistant".equals(m.getRole()) && "回答".equals(m.getContent())));
            } finally { AgentMetadataStore.removeOnOwn(agent.getAgentId()); }
        }
    }
}
