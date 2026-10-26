package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.engine.log.ChatLogHook;
import com.gmd.faq.server.main.engine.log.ConcurrentLogProducer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectToolHistoryTest {
    @Test void realDirectTurnPersistsItsFinalAnswerOnceWithoutModelPostReasoning() {
        var logs = new CopyOnWriteArrayList<ChatMessage>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new PermissionAwareTool(new ToolBase(ToolBase.builder().name("direct")
                .description("direct").inputSchema(Map.of("type", "object", "properties", Map.of())).returnDirect(true)) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam input) { return Mono.just(ToolResultBlock.text("原始答复")); }
        }));
        Model model = new Model() {
            public String getModelName() { return "direct-history"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(List.of(ToolUseBlock.builder()
                        .id("call").name("direct").input(Map.of()).build())).build());
            }
        };
        var logging = new ChatLogHook();
        // SDK 在工具完成后切换线程，静态模拟必须在实际 hook 回调线程创建。
        Hook capture = new Hook() {
            @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
                try (var producer = mockStatic(ConcurrentLogProducer.class)) {
                    producer.when(() -> ConcurrentLogProducer.pushLog(any())).thenAnswer(invocation -> {
                        logs.add(invocation.getArgument(0)); return null;
                    });
                    return logging.onEvent(event);
                }
            }
        };
        var agent = ReActAgent.builder().name("direct-history").model(model).toolkit(toolkit)
                .hooks(List.of(capture)).maxIters(2).build();
        try (agent) {
            AgentMetadataStore.put(agent.getAgentId(), "tenantId", 1L);
            AgentMetadataStore.put(agent.getAgentId(), "threadId", "42");
            assertEquals("原始答复", agent.call(Msg.builder().role(MsgRole.USER).textContent("查询").build()).block(Duration.ofSeconds(10)).getTextContent());
            assertEquals(1, logs.size()); assertEquals("assistant", logs.getFirst().getRole());
            assertEquals("原始答复", logs.getFirst().getContent()); assertEquals(42L, logs.getFirst().getSessionId());
        } finally {
            AgentMetadataStore.removeOnOwn(agent.getAgentId());
        }
    }
}
