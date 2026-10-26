package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.engine.hook.builtins.IConfirmationHook;
import com.gmd.faq.server.main.engine.agent.AgentExecutionRole;
import com.gmd.faq.server.main.engine.plan.AgentPlanService;
import com.gmd.faq.server.main.engine.plan.PlanGuardedTool;
import com.gmd.faq.server.main.engine.tool.dynamices.DynamicAgentTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReturnDirectToolTest {
    @ParameterizedTest
    @EnumSource(value = ToolResultState.class, names = {"SUCCESS", "ERROR", "DENIED", "INTERRUPTED"})
    void onlySuccessfulToolsShortCircuitTheRealReactLoop(ToolResultState resultState) {
        var calls = new AtomicInteger();
        var effects = new AtomicInteger();
        var nativeTool = new ToolBase(ToolBase.builder().name("lookup").description("lookup")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                effects.incrementAndGet();
                return Mono.just(ToolResultBlock.text("原始结果").withState(resultState));
            }
        };
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new PlanGuardedTool(new PermissionAwareTool(nativeTool, true),
                mock(AgentPlanService.class), true, false));
        Model model = new Model() {
            public String getModelName() { return "direct-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ContentBlock block = calls.incrementAndGet() == 1 ? call("lookup") : TextBlock.builder().text("模型处理了失败").build();
                return Flux.just(ChatResponse.builder().content(List.of(block)).build());
            }
        };
        try (var agent = ReActAgent.builder().name("direct").model(model).toolkit(toolkit).maxIters(3).build()) {
            var events = agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("查询").build())
                    .collectList().block(Duration.ofSeconds(10));
            assertEquals(1, effects.get());
            assertEquals(resultState == ToolResultState.SUCCESS ? 1 : 2, calls.get());
            assertTrue(events.stream().anyMatch(e -> e instanceof TextBlockDeltaEvent delta
                    && delta.getDelta().equals(resultState == ToolResultState.SUCCESS ? "原始结果" : "模型处理了失败")));
        }
    }

    @Test void wrappingPreservesSchemasMcpSafetyAndRuleSemantics() {
        var nativeTool = new ToolBase(ToolBase.builder().name("native").description("native")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .readOnly(true).concurrencySafe(true).stateInjected(true).externalTool(true).mcp("server")) {
            @Override public Map<String, Object> getOutputSchema() { return Map.of("type", "object"); }
            @Override public Boolean getStrict() { return true; }
            @Override public boolean matchRule(String pattern, Map<String, Object> input) { return "specific".equals(pattern); }
            @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState state) {
                return Mono.just(PermissionDecision.deny("native safety policy"));
            }
            @Override public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
                return List.of(new PermissionRule("native", "specific", PermissionBehavior.ASK, "native"));
            }
        };
        var wrapped = new PlanGuardedTool(new PermissionAwareTool(nativeTool, true, true), mock(AgentPlanService.class), true, false);
        assertTrue(wrapped.isReadOnly()); assertTrue(wrapped.isReturnDirect()); assertTrue(wrapped.isConcurrencySafe());
        assertTrue(wrapped.requiresConfirmation());
        assertTrue(wrapped.isStateInjected()); assertTrue(wrapped.isExternalTool()); assertTrue(wrapped.isMcp());
        assertEquals("server", wrapped.getMcpName()); assertEquals(nativeTool.getOutputSchema(), wrapped.getOutputSchema());
        assertTrue(wrapped.getStrict()); assertTrue(wrapped.matchRule("specific", Map.of()));
        assertFalse(wrapped.matchRule(null, Map.of()));
        assertEquals(nativeTool.generateSuggestions(Map.of()), wrapped.generateSuggestions(Map.of()));
        assertEquals(PermissionBehavior.DENY, wrapped.checkPermissions(Map.of(), PermissionContextState.builder().build()).block().getBehavior());
    }

    @Test void failedDynamicCodeIsAnErrorInsteadOfAValidDirectAnswer() {
        var config = new ToolConfig(); config.setToolId("broken"); config.setReturnDirect(true);
        var tool = new DynamicAgentTool(config);
        var result = tool.callAsync(ToolCallParam.builder().toolUseBlock(call("broken")).input(Map.of()).build()).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(tool.isReturnDirect());
    }

    @Test void identicallyNamedToolsKeepIndependentConfirmationRulesAfterSdkToolkitCopies() {
        var confirmed = new Toolkit();
        var ordinary = new Toolkit();
        ToolkitFactory.registerTools(confirmed, List.of(config(true)));
        ToolkitFactory.registerTools(ordinary, List.of(config(false)));
        assertEquals(1, IConfirmationHook.rulesFor(confirmed).size());
        assertTrue(IConfirmationHook.rulesFor(ordinary).isEmpty());
        for (var toolkit : List.of(confirmed, ordinary)) {
            var rules = PermissionContextState.builder()
                    .addAllowRule("shared", new PermissionRule("shared", null, PermissionBehavior.ALLOW, "test"));
            IConfirmationHook.rulesFor(toolkit).forEach(rule -> rules.addAskRule(rule.toolName(), rule));
            Model model = new Model() {
                public String getModelName() { return "confirmation-isolation"; }
                public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    var block = messages.getLast().getContentBlocks(ToolResultBlock.class).isEmpty()
                            ? call("shared") : TextBlock.builder().text("完成").build();
                    return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                }
            };
            try (var agent = ReActAgent.builder().name("isolation").toolkit(toolkit).model(model)
                    .permissionContext(rules.build()).maxIters(2).build()) {
                var events = agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("执行").build())
                        .collectList().block(Duration.ofSeconds(10));
                assertEquals(toolkit == confirmed ? 1 : 0, events.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
            }
        }
    }

    private static ToolConfig config(boolean confirmation) {
        var tool = new ToolConfig(); tool.setToolId("shared"); tool.setDescription("same name");
        tool.setToolType(ToolType.CUSTOM); tool.setEnabled(true); tool.setNeedConfirm(confirmation);
        tool.setInputSchema(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
        return tool;
    }

    @Test void lazyMcpConfirmationIsInstanceScopedAndDelegationFiltersItsActualRequirement() {
        var servers = mock(com.gmd.faq.server.main.mcp.service.McpServerService.class);
        var bindings = mock(com.gmd.faq.server.main.mcp.service.AgentMcpServerService.class);
        var tools = mock(com.gmd.faq.server.main.mcp.service.McpToolService.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var factory = new com.gmd.faq.server.main.engine.mcp.McpClientFactory(servers, bindings, tools,
                mock(com.gmd.faq.server.main.mcp.service.McpRuntimeDegradeService.class), mapper);
        var server = new com.gmd.faq.server.main.common.entity.McpServer(); server.setId(42L);
        server.setName("mcp"); server.setEnabled(true);
        server.setActivationStatus(com.gmd.faq.server.main.common.enums.McpActivationStatus.ACTIVE);
        var binding = new com.gmd.faq.server.main.common.vo.AgentMcpBindingVO(); binding.setMcpServerId(42L);
        when(servers.getById(42L)).thenReturn(server);
        when(bindings.getBindings(1L)).thenReturn(List.of(binding));
        var row = new com.gmd.faq.server.main.common.entity.McpTool(); row.setNeedConfirm(true);
        row.setRawSchema(mapper.valueToTree(Map.of("name", "shared", "description", "MCP",
                "inputSchema", Map.of("type", "object", "properties", Map.of()))));
        when(tools.listRuntimeTools(42L)).thenReturn(List.of(row));
        var definition = new com.gmd.faq.server.main.common.entity.AgentDefinition(); definition.setId(1L);
        var confirmed = new PermissionAwareTool(factory.getLazyMcpTools(definition).getFirst());
        row.setNeedConfirm(false);
        var ordinary = new PermissionAwareTool(factory.getLazyMcpTools(definition).getFirst());
        assertTrue(confirmed.requiresConfirmation()); assertFalse(ordinary.requiresConfirmation());
        assertTrue(confirmed.isMcp()); assertEquals("mcp", confirmed.getMcpName());
        assertFalse(ToolkitFactory.allowToolInRole(confirmed, AgentExecutionRole.SUBAGENT));
        assertTrue(ToolkitFactory.allowToolInRole(ordinary, AgentExecutionRole.SUBAGENT));
        assertTrue(ToolkitFactory.allowToolInRole(confirmed, AgentExecutionRole.ROOT));
    }

    private static ToolUseBlock call(String name) {
        return ToolUseBlock.builder().id("call-" + name).name(name).input(Map.of()).content("{}").build();
    }
}
