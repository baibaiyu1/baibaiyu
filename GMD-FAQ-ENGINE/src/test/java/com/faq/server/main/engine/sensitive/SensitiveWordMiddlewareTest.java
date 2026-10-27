package com.gmd.faq.server.main.engine.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.SensitiveWordAction;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.engine.log.ChatLogHook;
import com.gmd.faq.server.main.engine.log.ConcurrentLogProducer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.*;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class SensitiveWordMiddlewareTest {
    private final AgentDefinition definition = new AgentDefinition();
    private final SensitiveWordConfig config = new SensitiveWordConfig();

    private SensitiveWordPolicy policy(SensitiveWordAction action) {
        definition.setSensitiveFilterEnabled(true); config.setEnabled(true); config.setAction(action);
        config.setWords(new ObjectMapper().valueToTree(List.of("敏感词", "审计$编号")));
        config.setReplacement("$1\\替换");
        return SensitiveWordPolicy.from(definition, config);
    }

    @Test void bothSwitchesAreRespectedAndLiteralReplacementIsSafe() {
        var enabled = policy(SensitiveWordAction.REPLACE);
        assertEquals("内容$1\\替换", enabled.input("内容审计$编号"));
        definition.setSensitiveFilterEnabled(false);
        assertFalse(SensitiveWordPolicy.from(definition, config).active());
        definition.setSensitiveFilterEnabled(true); config.setEnabled(false);
        assertFalse(SensitiveWordPolicy.from(definition, config).active());
    }

    @Test void blockedInputNeverCallsTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        Model model = model((messages, tools, options) -> { calls.incrementAndGet(); return Flux.just(text("不会调用")); });
        try (var agent = ReActAgent.builder().name("filter").model(model)
                .middleware(new SensitiveWordMiddleware(policy(SensitiveWordAction.BLOCK))).build()) {
            for (List<ContentBlock> blocks : List.of(List.<ContentBlock>of(TextBlock.builder().text("包含敏感词").build()),
                    List.<ContentBlock>of(TextBlock.builder().text("包含敏").build(), TextBlock.builder().text("感词").build()))) {
                assertThrows(Exception.class, () -> agent.call(Msg.builder().role(MsgRole.USER).content(blocks).build()).block());
            }
            assertEquals(0, calls.get());
        }
    }

    @Test void replacementReachesTheModelAndThePersistedAssistantWithoutLeakingStreamFragments() {
        Model model = model((messages, tools, options) -> {
            assertTrue(messages.stream().filter(m -> m.getRole() == MsgRole.USER).allMatch(m -> !m.getTextContent().contains("敏感词")));
            return Flux.just(text("说明敏"), text("感词结束"));
        });
        try (var agent = ReActAgent.builder().name("filter").model(model)
                .middleware(new SensitiveWordMiddleware(policy(SensitiveWordAction.REPLACE))).build()) {
            var result = agent.call(Msg.builder().role(MsgRole.USER).content(List.of(
                    TextBlock.builder().text("输入敏").build(), TextBlock.builder().text("感词").build())).build())
                    .block(Duration.ofSeconds(10));
            assertEquals("说明$1\\替换结束", result.getTextContent());
        }
        var chunks = new SensitiveWordModel(model((m,t,o) -> Flux.just(text("说明敏"), text("感词结束"))),
                policy(SensitiveWordAction.REPLACE), () -> fail("not WARN")).stream(List.of(), List.of(), null).collectList().block();
        assertEquals("说明$1\\替换结束", chunks.stream().flatMap(c -> c.getContent().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).collect(java.util.stream.Collectors.joining()));
        assertTrue(chunks.stream().flatMap(c -> c.getContent().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).noneMatch(block -> block.getText().contains("敏") || block.getText().contains("感词")));
    }

    @Test void blockedResponseDoesNotExposeThinkingOrToolCallsAndPreservesActualUsage() {
        ChatUsage usage = ChatUsage.builder().inputTokens(10).outputTokens(4).build();
        Model provider = model((m,t,o) -> Flux.just(text("敏"), ChatResponse.builder().usage(usage).content(List.of(
                TextBlock.builder().text("感词").build(), ThinkingBlock.builder().thinking("敏感词").metadata(Map.of("signature", "kept")).build(),
                ToolUseBlock.builder().id("call").name("must_not_run").input(Map.of()).build())).build()));
        var chunks = new SensitiveWordModel(provider, policy(SensitiveWordAction.BLOCK), () -> {}).stream(List.of(), List.of(), null).collectList().block();
        assertEquals(1, chunks.size()); assertSame(usage, chunks.getFirst().getUsage());
        assertEquals(List.of(SensitiveWordPolicy.BLOCKED_REPLY), chunks.getFirst().getContent().stream().map(TextBlock.class::cast).map(TextBlock::getText).toList());
    }

    @Test void warnContinuesAndEmitsInputAndOutputNotifications() {
        Model provider = model((m,t,o) -> Flux.just(text("敏"), text("感词原文")));
        try (var agent = ReActAgent.builder().name("filter").model(provider)
                .middleware(new SensitiveWordMiddleware(policy(SensitiveWordAction.WARN))).build()) {
            var events = agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("敏感词").build())
                    .collectList().block(Duration.ofSeconds(10));
            assertEquals(2, events.stream().filter(CustomEvent.class::isInstance).map(CustomEvent.class::cast)
                    .filter(e -> "SENSITIVE_WORD_WARNING".equals(e.getName())).count());
            assertTrue(events.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
                    .anyMatch(e -> e.getResult().getTextContent().equals("敏感词原文")));
        }
    }

    private ChatResponse text(String text) { return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).build(); }
    @ParameterizedTest
    @EnumSource(SensitiveWordAction.class)
    void directReturnFiltersTheStreamAndChatLogWhileKeepingActualToolOutput(SensitiveWordAction action) {
        var logs = new java.util.concurrent.CopyOnWriteArrayList<ChatMessage>();
        var logging = new ChatLogHook();
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
        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerAgentTool(new io.agentscope.core.tool.ToolBase(io.agentscope.core.tool.ToolBase.builder()
                .name("lookup").description("lookup").returnDirect(true).inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            @Override public reactor.core.publisher.Mono<ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam input) {
                return Mono.just(ToolResultBlock.of(List.of(TextBlock.builder().text("敏").build(),
                        TextBlock.builder().text("感词原始结果").build())));
            }
        });
        Model provider = model((m,t,o) -> Flux.just(ChatResponse.builder().content(List.of(
                ToolUseBlock.builder().id("lookup-call").name("lookup").input(Map.of()).content("{}").build())).build()));
        var policy = policy(action);
        try (var agent = ReActAgent.builder().name("filter").model(provider).toolkit(toolkit).maxIters(2)
                .hooks(List.of(capture, new SensitiveWordDirectReturnHook(policy)))
                .middleware(new SensitiveWordMiddleware(policy)).build()) {
            AgentMetadataStore.put(agent.getAgentId(), "tenantId", 1L);
            AgentMetadataStore.put(agent.getAgentId(), "threadId", "42");
            var context = io.agentscope.core.agent.RuntimeContext.builder().userId("test").sessionId("direct").build();
            var events = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("查询").build()), context)
                    .collectList().block(Duration.ofSeconds(10));
            String expected = switch (action) {
                case BLOCK -> SensitiveWordPolicy.BLOCKED_REPLY;
                case REPLACE -> "$1\\替换原始结果";
                case WARN -> "敏感词原始结果";
            };
            assertEquals(expected, events.stream().filter(TextBlockDeltaEvent.class::isInstance).map(TextBlockDeltaEvent.class::cast)
                    .map(TextBlockDeltaEvent::getDelta).collect(java.util.stream.Collectors.joining()));
            assertTrue(events.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
                    .anyMatch(event -> event.getResult().getTextContent().replace("\n", "").equals(expected)));
            assertEquals(expected, logs.stream().filter(log -> "assistant".equals(log.getRole()))
                    .map(ChatMessage::getContent).collect(java.util.stream.Collectors.joining()));
            // 工具详情仍然展示真实内容；SDK 已保存的工具和回复历史不被投影过滤改写。
            assertEquals("敏感词原始结果", events.stream().filter(ToolResultTextDeltaEvent.class::isInstance)
                    .map(ToolResultTextDeltaEvent.class::cast).map(ToolResultTextDeltaEvent::getDelta)
                    .collect(java.util.stream.Collectors.joining()));
            assertTrue(agent.getAgentState(context).getContext().stream()
                    .filter(m -> Boolean.TRUE.equals(m.getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT)))
                    .anyMatch(m -> m.getTextContent().replace("\n", "").equals("敏感词原始结果")));
            assertEquals(action == SensitiveWordAction.WARN ? 1 : 0, events.stream().filter(CustomEvent.class::isInstance)
                    .map(CustomEvent.class::cast).filter(e -> "SENSITIVE_WORD_WARNING".equals(e.getName())).count());
            AgentMetadataStore.removeOnOwn(agent.getAgentId());
        }
    }
    private Model model(Stream stream) {
        return new Model() {
            public String getModelName() { return "sensitive-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) { return stream.run(messages, tools, options); }
        };
    }
    private interface Stream { Flux<ChatResponse> run(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options); }
}
