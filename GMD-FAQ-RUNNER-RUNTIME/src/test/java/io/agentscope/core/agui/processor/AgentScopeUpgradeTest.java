package io.agentscope.core.agui.processor;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agent.AgentSessionPolicy;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.PermissionAwareTool;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.engine.tool.ToolResultImageSupport;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.engine.workspace.hook.ToolResultImageHook;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.*;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.hook.Hook;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeUpgradeTest {
    @AfterEach void cleanup() { AgentContext.clean(); TenantUtils.clear(); }

    @Test void approvalSurvivesAgentRebuildAndUsesPersistedArguments() {
        var store = new InMemoryAgentStateStore();
        var calls = new AtomicInteger();
        var rc = RuntimeContext.builder().userId("tenant_1_user_2").sessionId("s").build();
        ReActAgent first = agent(store, calls);
        var adapter = new AguiAgentAdapter(first, AguiAdapterConfig.defaultConfig());
        var events = adapter.runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("执行").build()), "s", "r", rc)
                .collectList().block(Duration.ofSeconds(10));
        assertTrue(adapter.isSuspended());
        assertEquals(0, calls.get());
        assertEquals(1, events.stream().filter(AguiEvent.RunFinished.class::isInstance).count());
        List<ToolUseBlock> pending = AguiRequestProcessor.pendingTools(store.get("tenant_1_user_2", "s", "agent_state", AgentState.class).orElseThrow());
        assertEquals(1, pending.size());
        ReActAgent rebuilt = agent(store, calls);
        rebuilt.call(AguiRequestProcessor.buildResumeInput(List.of(), pending), rc).block(Duration.ofSeconds(10));
        assertEquals(1, calls.get());
        assertTrue(AguiRequestProcessor.pendingTools(rebuilt.getAgentState(rc)).isEmpty());
        assertFalse(store.exists("tenant_1_user_3", "s"));
    }

    @Test void rejectedToolCannotRunAgainInSameTurnButCanAskNextTurn() {
        var store = new InMemoryAgentStateStore();
        var calls = new AtomicInteger();
        var rc = RuntimeContext.builder().userId("u").sessionId("s").build();
        agent(store, calls).call(List.of(Msg.builder().role(MsgRole.USER).textContent("执行").build()), rc).block(Duration.ofSeconds(10));
        var rebuilt = agent(store, calls);
        var pending = AguiRequestProcessor.pendingTools(rebuilt.getAgentState(rc));
        rebuilt.call(AguiRequestProcessor.buildResumeInput(List.of(
                new AguiRequestProcessor.ResumeDecision(pending.getFirst().getId(), "sensitive", false)), pending), rc).block(Duration.ofSeconds(10));
        assertEquals(0, calls.get());
        assertTrue(rebuilt.getAgentState(rc).getPermissionContext().getDenyRules().containsKey("sensitive"));
        rebuilt.call(List.of(Msg.builder().role(MsgRole.USER).textContent("重新执行").build()), rc).block(Duration.ofSeconds(10));
        assertFalse(rebuilt.getAgentState(rc).getPermissionContext().getDenyRules().containsKey("sensitive"));
        assertEquals(1, AguiRequestProcessor.pendingTools(rebuilt.getAgentState(rc)).size());
    }

    @Test void rejectsForgedNamesUnknownIdsAndDuplicateDecisions() {
        var pending = List.of(new ToolUseBlock("call", "sensitive", Map.of("value", "server")));
        assertThrows(IllegalArgumentException.class, () -> AguiRequestProcessor.buildResumeInput(
                List.of(new AguiRequestProcessor.ResumeDecision("other", "sensitive", true)), pending));
        assertThrows(IllegalArgumentException.class, () -> AguiRequestProcessor.buildResumeInput(
                List.of(new AguiRequestProcessor.ResumeDecision("call", "other", true)), pending));
        var decision = new AguiRequestProcessor.ResumeDecision("call", "sensitive", true);
        assertThrows(IllegalArgumentException.class, () -> AguiRequestProcessor.buildResumeInput(List.of(decision, decision), pending));
    }

    @Test void updatingPermissionsPreservesMessagesSavedByAnotherInstance() {
        var store = new InMemoryAgentStateStore();
        var rc = RuntimeContext.builder().userId("u").sessionId("s").build();
        ReActAgent cached = agent(store, new AtomicInteger());
        cached.getAgentState(rc);
        store.save("u", "s", "agent_state", AgentState.builder().userId("u").sessionId("s")
                .context(List.of(Msg.builder().role(MsgRole.USER).textContent("另一实例保存的历史").build())).build());
        cached.call(List.of(Msg.builder().role(MsgRole.USER).textContent("继续").build()), rc).block(Duration.ofSeconds(10));
        assertTrue(cached.getAgentState(rc).getContext().stream()
                .anyMatch(message -> "另一实例保存的历史".equals(message.getTextContent())));
    }

    @Test void nativeModelFailureEmitsErrorAndOneTerminalEvent() {
        Model model = new Model() {
            public String getModelName() { return "failing"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new IllegalStateException("fixture model unavailable"));
            }
        };
        try (ReActAgent agent = ReActAgent.builder().name("failing").model(model).maxRetries(1).build()) {
            var events = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig()).runWithMessages(
                    List.of(Msg.builder().role(MsgRole.USER).textContent("执行").build()), "s", "r")
                    .collectList().block(Duration.ofSeconds(10));
            assertEquals(1, events.stream().filter(AguiEvent.RunError.class::isInstance).count(), events.toString());
            assertEquals(1, events.stream().filter(AguiEvent.RunFinished.class::isInstance).count(), events.toString());
        }
    }

    @Test void temporaryApprovalKeepsPausedOptionsWhenAgentSettingsChange() {
        var store = new InMemoryAgentStateStore();
        var calls = new AtomicInteger();
        String durable = AgentStateScope.userId(1L, 2L);
        store.save(durable, "preview", "agent_state", AgentState.builder().userId(durable).sessionId("preview")
                .context(List.of(Msg.builder().role(MsgRole.USER).textContent("已有持久历史").build())).build());
        AgentResolver resolver = org.mockito.Mockito.mock(AgentResolver.class);
        org.mockito.Mockito.when(resolver.resolveAgent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> agent(store, calls));
        var processor = AguiRequestProcessor.builder().stateStore(store).agentResolver(resolver).build();
        AgentContext context = principal();
        context.getAgentDefinition().setEnableMemory(false);
        context.getAgentDefinition().setEnablePlanning(true);
        context.getAgentDefinition().setShowToolProcess(true);
        AgentContext.set(context);
        var result = processor.process(RunAgentInput.builder().threadId("preview").runId("r")
                .messages(List.of(AguiMessage.userMessage("msg", "执行")))
                .forwardedProps(Map.of("memoryActive", false)).build(), null, null);
        result.events().collectList().block(Duration.ofSeconds(10));
        assertTrue(store.exists(durable + "_transient", "preview"));
        AgentContext updated = principal();
        updated.getAgentDefinition().setEnablePlanning(false);
        updated.getAgentDefinition().setShowToolProcess(false);
        AgentContext.set(updated);
        var resumed = processor.resume("preview", List.of(), true);
        assertTrue(AgentContext.get().isPlanActive());
        assertFalse(AgentContext.get().isMemoryActive());
        assertEquals(true, com.gmd.faq.server.main.common.util.AgentMetadataStore.get(resumed.agent().getAgentId(), "toolProcessActive"));
        resumed.events().collectList().block(Duration.ofSeconds(10));
        assertEquals(List.of("已有持久历史"), store.get(durable, "preview", "agent_state", AgentState.class).orElseThrow()
                .getContext().stream().map(Msg::getTextContent).toList());
        assertFalse(store.exists(durable + "_transient", "preview"));
        assertEquals(1, calls.get());
    }

    private static AgentContext principal() {
        AgentContext context = new AgentContext();
        context.setTenantId(1L); context.setTenantCode("test"); context.setThreadId("preview");
        AccountVO user = new AccountVO(); user.setId(2L); context.setUserInfo(user);
        AgentDefinition definition = new AgentDefinition(); definition.setEnableMemory(true);
        context.setAgentDefinition(definition);
        return context;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void agentSettingsOverrideConflictingClientFlags(boolean enabled) {
        var store = new InMemoryAgentStateStore();
        AgentResolver resolver = org.mockito.Mockito.mock(AgentResolver.class);
        var created = new java.util.ArrayList<ReActAgent>();
        org.mockito.Mockito.when(resolver.resolveAgent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> { var agent = agent(store, new AtomicInteger()); created.add(agent); return agent; });
        var processor = AguiRequestProcessor.builder().stateStore(store).agentResolver(resolver).build();
        try {
            AgentContext context = principal();
            context.setPlanActive(!enabled);
            context.getAgentDefinition().setEnableMemory(enabled);
            context.getAgentDefinition().setEnablePlanning(enabled);
            context.getAgentDefinition().setShowToolProcess(enabled);
            AgentContext.set(context);
            var result = processor.process(RunAgentInput.builder().threadId("preview").runId("r")
                    .messages(List.of(AguiMessage.userMessage("msg", "执行")))
                    .forwardedProps(Map.of("memoryActive", !enabled, "planActive", !enabled, "toolProcessActive", !enabled))
                    .build(), null, null);
            assertEquals(enabled, context.isMemoryActive());
            assertEquals(enabled, context.isPlanActive());
            assertEquals(enabled, com.gmd.faq.server.main.common.util.AgentMetadataStore.get(result.agent().getAgentId(), "toolProcessActive"));
            assertEquals(AgentStateScope.userId(1L, 2L) + (enabled ? "" : "_transient"), result.runtimeContext().getUserId());
            var events = result.events().collectList().block(Duration.ofSeconds(10));
            assertInstanceOf(AguiEvent.RunStarted.class, events.getFirst());
            assertEquals("CHAT_RUN_OPTIONS", assertInstanceOf(AguiEvent.Custom.class, events.get(1)).name());
            assertTrue(events.stream().anyMatch(event -> event instanceof AguiEvent.Custom custom
                    && "CHAT_RUN_OPTIONS".equals(custom.name()) && Map.of(
                            "memoryActive", enabled, "planActive", enabled, "toolProcessActive", enabled).equals(custom.value())));
        } finally { created.forEach(agent -> { agent.close(); com.gmd.faq.server.main.common.util.AgentMetadataStore.removeOnOwn(agent.getAgentId()); }); }
    }

    @Test void olderPausedOptionsRemainReadable() throws Exception {
        var options = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"planActive\":true}", AguiRequestProcessor.SavedCallOptions.class);
        assertTrue(options.planActive());
        assertNull(options.memoryActive());
        assertNull(options.toolProcessActive());
    }

    @Test void rebuiltApprovalRestoresWorkspaceAndStreamsNormalizedImage() {
        var store = new InMemoryAgentStateStore();
        var calls = new AtomicInteger();
        var imageSupport = org.mockito.Mockito.mock(ToolResultImageSupport.class);
        Path workspace = Path.of(SysConst.getWorkspacePath("test"), "preview");
        org.mockito.Mockito.when(imageSupport.normalize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(workspace)))
                .thenAnswer(invocation -> {
                    ToolResultBlock result = invocation.getArgument(0);
                    return ToolResultBlock.of(result.getId(), result.getName(), result.getOutput(), Map.of(
                            ToolResultPresentation.METADATA_KEY, List.of(new ToolResultPresentation.Image(
                                    ".tool-results/fixture.png", "fixture.png", "png", "image/png", 10L, null))));
                });
        AgentResolver resolver = org.mockito.Mockito.mock(AgentResolver.class);
        var created = new java.util.ArrayList<ReActAgent>();
        org.mockito.Mockito.when(resolver.resolveAgent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    var rebuilt = agent(store, calls, List.of(new ToolResultImageHook(imageSupport)));
                    created.add(rebuilt);
                    return rebuilt;
                });
        var processor = AguiRequestProcessor.builder().stateStore(store).agentResolver(resolver).build();
        try {
            AgentContext.set(principal());
            processor.process(RunAgentInput.builder().threadId("preview").runId("r")
                    .messages(List.of(AguiMessage.userMessage("msg", "执行")))
                    .forwardedProps(Map.of("memoryActive", true)).build(), null, null)
                    .events().collectList().block(Duration.ofSeconds(10));
            AgentContext.set(principal());
            var events = processor.resume("preview", List.of(), true).events()
                    .collectList().block(Duration.ofSeconds(10));
            assertEquals(1, calls.get());
            assertEquals(1, events.stream().filter(event -> event instanceof AguiEvent.Custom custom
                    && ToolResultPresentation.EVENT_NAME.equals(custom.name())).count(), events.toString());
            org.mockito.Mockito.verify(imageSupport).normalize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(workspace));
        } finally {
            created.forEach(agent -> {
                agent.close();
                com.gmd.faq.server.main.common.util.AgentMetadataStore.removeOnOwn(agent.getAgentId());
            });
        }
    }

    @Test void disablingMemoryDuringApprovalDoesNotDeleteDurableHistory() {
        var store = new InMemoryAgentStateStore();
        var calls = new AtomicInteger();
        AgentResolver resolver = org.mockito.Mockito.mock(AgentResolver.class);
        org.mockito.Mockito.when(resolver.resolveAgent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> agent(store, calls));
        var processor = AguiRequestProcessor.builder().stateStore(store).agentResolver(resolver).build();
        AgentContext.set(principal());
        processor.process(RunAgentInput.builder().threadId("preview").runId("r")
                .messages(List.of(AguiMessage.userMessage("msg", "执行")))
                .forwardedProps(Map.of("memoryActive", true)).build(), null, null)
                .events().collectList().block(Duration.ofSeconds(10));
        AgentContext updated = principal();
        updated.getAgentDefinition().setEnableMemory(false);
        AgentContext.set(updated);
        var resumed = processor.resume("preview", List.of(), false);
        assertTrue(AgentContext.get().isMemoryActive());
        resumed.events().collectList().block(Duration.ofSeconds(10));
        String durable = AgentStateScope.userId(1L, 2L);
        assertTrue(store.exists(durable, "preview"));
        assertTrue(AguiRequestProcessor.pendingTools(store.get(durable, "preview", "agent_state", AgentState.class).orElseThrow()).isEmpty());
        assertEquals(1, calls.get());
    }

    private static ReActAgent agent(AgentStateStore store, AtomicInteger calls) {
        return agent(store, calls, List.of());
    }

    private static ReActAgent agent(AgentStateStore store, AtomicInteger calls, List<Hook> hooks) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new PermissionAwareTool(new AgentTool() {
            public String getName() { return "sensitive"; }
            public String getDescription() { return "敏感工具"; }
            public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                assertEquals("server", param.getInput().get("value"));
                calls.incrementAndGet();
                return Mono.just(ToolResultBlock.of(TextBlock.builder().text("已执行").build()));
            }
        }));
        var permissions = PermissionContextState.builder()
                .addAllowRule("sensitive", new PermissionRule("sensitive", null, PermissionBehavior.ALLOW, "test"))
                .addAskRule("sensitive", new PermissionRule("sensitive", null, PermissionBehavior.ASK, "test")).build();
        Model model = new Model() {
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
                var blocks = msgs.getLast().getContentBlocks(ToolResultBlock.class);
                return Flux.just(ChatResponse.builder().content(blocks.isEmpty()
                        ? List.of(new ToolUseBlock(java.util.UUID.randomUUID().toString(), "sensitive", Map.of("value", "server")))
                        : List.of(TextBlock.builder().text("完成").build())).build());
            }
            public String getModelName() { return "test"; }
        };
        return ReActAgent.builder().name("test").model(model).toolkit(toolkit).stateStore(store).maxIters(3)
                .permissionContext(permissions).middleware(new AgentSessionPolicy(false, permissions)).hooks(hooks).build();
    }
}
