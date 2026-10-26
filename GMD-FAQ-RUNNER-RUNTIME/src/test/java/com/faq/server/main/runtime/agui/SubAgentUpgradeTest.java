package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.subagent.SubAgentTraceEvent;
import com.gmd.faq.server.main.common.subagent.SubAgentTraceEventType;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.PermissionAwareTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentUpgradeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @AfterEach void cleanup() { AgentContext.clean(); TenantUtils.clear(); }

    @Test void registrationPreservesParentContextAndSkipsUnnecessaryConstruction() {
        AgentContext parent = context(1L, 2L, "parent");
        AgentContext.set(parent);
        TenantUtils.setCurrentTenant(1L, "tenant-1");
        var calls = new AtomicInteger();
        SubAgentProvider<ReActAgent> provider = () -> {
            calls.incrementAndGet();
            AgentContext.get().setAgentDefinition(definition(20L));
            TenantUtils.setCurrentTenant(99L, "child");
            return ReActAgent.builder().name("child").model(textModel()).build();
        };
        new SubAgentTool(provider, SubAgentConfig.builder().toolName("child").description("child").build());
        assertEquals(0, calls.get());
        assertEquals("call_child", new SubAgentTool(provider, null).getName());
        assertEquals(1, calls.get());
        assertSame(parent, AgentContext.get());
        assertEquals(10L, parent.getAgentDefinition().getId());
        assertEquals(1L, TenantUtils.getCurrentTenantId());
    }

    @Test void subagentRetainsMultiTurnHistoryAndIsolatesUserAndParentSession() {
        var persisted = new InMemoryAgentStateStore();
        List<Integer> historySizes = new ArrayList<>();
        var tool = new SubAgentTool(() -> {
            AgentContext child = AgentContext.get();
            child.setAgentDefinition(definition(20L));
            Model model = new Model() {
                public String getModelName() { return "history"; }
                public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.defer(() -> {
                        assertSame(child, AgentContext.get());
                        assertEquals(child.getTenantId(), TenantUtils.getCurrentTenantId());
                        assertEquals(20L, AgentContext.get().getAgentDefinition().getId());
                        historySizes.add((int) msgs.stream().filter(msg -> msg.getRole() == MsgRole.USER).count());
                        return Flux.just(reply("done"));
                    }).subscribeOn(Schedulers.boundedElastic());
                }
            };
            return ReActAgent.builder().name("child").model(model).stateStore(new InMemoryAgentStateStore()).build();
        }, SubAgentConfig.builder().toolName("child").description("child").stateStore(persisted).build());
        AgentContext parent = context(1L, 2L, "p1");
        ToolResultBlock first = invoke(tool, parent, Map.of("message", "first"));
        String sessionId = first.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .findFirst().orElseThrow().getText().split("\\n")[0].substring("session_id: ".length());
        invoke(tool, parent, Map.of("session_id", sessionId, "message", "second"));
        invoke(tool, context(1L, 3L, "p1"), Map.of("session_id", sessionId, "message", "other user"));
        invoke(tool, context(2L, 2L, "p1"), Map.of("session_id", sessionId, "message", "other tenant"));
        invoke(tool, context(1L, 2L, "p2"), Map.of("session_id", sessionId, "message", "other parent"));
        assertEquals(List.of(1, 2, 1, 1, 1), historySizes);
        assertEquals(2, persisted.listSessionIds(AgentStateScope.userId(1L, 2L)).size());
        assertEquals(10L, parent.getAgentDefinition().getId());
        assertEquals(1L, TenantUtils.getCurrentTenantId());
    }

    @Test void nativeHumanConfirmationBlocksChildInsteadOfReportingSuccess() {
        List<SubAgentTraceEvent> traces = new ArrayList<>();
        var tool = new SubAgentTool(() -> {
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(new PermissionAwareTool(new AgentTool() {
                public String getName() { return "sensitive"; }
                public String getDescription() { return "sensitive"; }
                public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
                public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return Mono.error(new AssertionError("must await approval")); }
            }));
            Model model = new Model() {
                public String getModelName() { return "asking"; }
                public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock("ask", "sensitive", Map.of()))).build());
                }
            };
            var permissions = PermissionContextState.builder().addAskRule("sensitive",
                    new PermissionRule("sensitive", null, PermissionBehavior.ASK, "test")).build();
            return ReActAgent.builder().name("child").model(model).toolkit(toolkit).permissionContext(permissions).build();
        }, SubAgentConfig.builder().toolName("child").description("child").build());
        AgentContext parent = context(1L, 2L, "p1");
        AgentContext.set(parent);
        var result = tool.callAsync(param(tool, parent, Map.of("message", "execute"), traces)).block(TIMEOUT);
        assertTrue(result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .anyMatch(block -> block.getText().contains("blocked")));
        assertTrue(traces.stream().anyMatch(event -> event.getEventType() == SubAgentTraceEventType.BLOCKED));
        assertFalse(traces.stream().anyMatch(event -> event.getEventType() == SubAgentTraceEventType.FINISHED));
    }

    @Test void cancelCleansChildMetadataAndEmitsCancelledTrace() {
        List<SubAgentTraceEvent> traces = new ArrayList<>();
        AtomicReference<ReActAgent> child = new AtomicReference<>();
        var tool = new SubAgentTool(() -> {
            Model model = new Model() {
                public String getModelName() { return "never"; }
                public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) { return Flux.never(); }
            };
            ReActAgent agent = ReActAgent.builder().name("child").model(model).build();
            child.set(agent);
            return agent;
        }, SubAgentConfig.builder().toolName("child").description("child").build());
        AgentContext parent = context(1L, 2L, "p1");
        AgentContext.set(parent);
        var subscription = tool.callAsync(param(tool, parent, Map.of("message", "execute"), traces)).subscribe();
        assertNotNull(AgentMetadataStore.get(child.get().getAgentId(), "threadId"));
        subscription.dispose();
        assertTrue(traces.stream().anyMatch(event -> event.getEventType() == SubAgentTraceEventType.CANCELLED));
        assertNull(AgentMetadataStore.get(child.get().getAgentId(), "threadId"));
        assertSame(parent, AgentContext.get());
    }

    @Test void nativeParentStreamFinishesSubagentToolAndForwardsTrace() {
        var tool = new SubAgentTool(() -> ReActAgent.builder().name("child").model(textModel()).build(),
                SubAgentConfig.builder().toolName("child").description("child").build());
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        Model model = new Model() {
            public String getModelName() { return "parent"; }
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(msgs.getLast().getContentBlocks(ToolResultBlock.class).isEmpty()
                        ? ChatResponse.builder().content(List.of(new ToolUseBlock("delegate", "child", Map.of("message", "work"),
                                "{\"message\":\"work\"}", Map.of()))).build()
                        : reply("finished"));
            }
        };
        AgentContext parent = context(1L, 2L, "p1");
        AgentContext.set(parent);
        var rc = runtime(parent);
        try (ReActAgent agent = ReActAgent.builder().name("parent").model(model).toolkit(toolkit).build()) {
            var events = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig()).runWithMessages(
                    List.of(Msg.builder().role(MsgRole.USER).textContent("delegate").build()), "p1", "r", rc)
                    .collectList().block(TIMEOUT);
            assertTrue(events.stream().noneMatch(AguiEvent.RunError.class::isInstance), events.toString());
            assertEquals(1, events.stream().filter(AguiEvent.ToolCallStart.class::isInstance).count());
            assertEquals(1, events.stream().filter(AguiEvent.ToolCallEnd.class::isInstance).count());
            assertEquals(1, events.stream().filter(AguiEvent.RunFinished.class::isInstance).count());
            assertTrue(events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                    .anyMatch(event -> SubAgentTraceEvent.CUSTOM_EVENT_NAME.equals(event.name())
                            && ((SubAgentTraceEvent) event.value()).getEventType() == SubAgentTraceEventType.FINISHED),
                    () -> events.toString());
        }
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void thinkingTraceRequiresBothParentAndChildDisplaySettings(boolean parentShow, boolean childShow) {
        var traces = new ArrayList<SubAgentTraceEvent>();
        Model childModel = new Model() {
            public String getModelName() { return "thinking-child"; }
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(List.of(ThinkingBlock.builder().thinking("子思考").build(),
                        TextBlock.builder().text("子回答").build())).build());
            }
        };
        var tool = new SubAgentTool(() -> ReActAgent.builder().name("child").model(childModel)
                .middleware(new com.gmd.faq.server.main.engine.agent.ReasoningDisplayMiddleware(childShow)).build(),
                SubAgentConfig.builder().toolName("child").description("child").build());
        AgentContext parentContext = context(1L, 2L, "p1"); AgentContext.set(parentContext);
        try (var parent = ReActAgent.builder().name("parent").model(textModel()).build()) {
            AgentMetadataStore.put(parent.getAgentId(), "showThinking", parentShow);
            try {
                var input = Map.<String, Object>of("message", "work");
                var result = tool.callAsync(ToolCallParam.builder().agent(parent).input(input).runtimeContext(runtime(parentContext))
                        .toolUseBlock(new ToolUseBlock("delegate", tool.getName(), input)).emitter(block -> {
                            if (block.getMetadata().get(SubAgentTraceEvent.METADATA_KEY) instanceof SubAgentTraceEvent trace) traces.add(trace);
                        }).build()).block(TIMEOUT);
                assertNotNull(result);
                assertEquals(parentShow && childShow, traces.stream().anyMatch(trace -> "thinking".equals(trace.getPayload().get("role"))));
                assertTrue(traces.stream().anyMatch(trace -> "子回答".equals(trace.getPayload().get("content"))));
            } finally { AgentMetadataStore.removeOnOwn(parent.getAgentId()); }
        }
    }

    private static ToolResultBlock invoke(SubAgentTool tool, AgentContext parent, Map<String, Object> input) {
        AgentContext.set(parent);
        TenantUtils.setCurrentTenant(parent.getTenantId(), parent.getTenantCode());
        var result = tool.callAsync(param(tool, parent, input, new ArrayList<>())).block(TIMEOUT);
        assertSame(parent, AgentContext.get());
        assertEquals(parent.getTenantId(), TenantUtils.getCurrentTenantId());
        return result;
    }

    private static ToolCallParam param(SubAgentTool tool, AgentContext parent, Map<String, Object> input, List<SubAgentTraceEvent> traces) {
        return ToolCallParam.builder().input(input).runtimeContext(runtime(parent))
                .toolUseBlock(new ToolUseBlock("delegate", tool.getName(), input))
                .emitter(block -> {
                    if (block.getMetadata().get(SubAgentTraceEvent.METADATA_KEY) instanceof SubAgentTraceEvent trace) traces.add(trace);
                }).build();
    }

    private static RuntimeContext runtime(AgentContext parent) {
        return RuntimeContext.builder().userId(AgentStateScope.userId(parent.getTenantId(), parent.getUserInfo().getId()))
                .sessionId(parent.getThreadId()).put(AgentContext.class, parent).build();
    }

    private static AgentContext context(Long tenant, Long user, String session) {
        AgentContext context = new AgentContext();
        context.setTenantId(tenant); context.setTenantCode("tenant-" + tenant);
        context.setThreadId(session); context.setRunId("run"); context.setMemoryActive(true);
        AccountVO account = new AccountVO(); account.setId(user); context.setUserInfo(account);
        context.setAgentDefinition(definition(10L));
        return context;
    }

    private static AgentDefinition definition(Long id) { AgentDefinition definition = new AgentDefinition(); definition.setId(id); return definition; }

    private static ChatResponse reply(String text) { return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).build(); }

    private static Model textModel() {
        return new Model() {
            public String getModelName() { return "text"; }
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) { return Flux.just(reply("done")); }
        };
    }
}
