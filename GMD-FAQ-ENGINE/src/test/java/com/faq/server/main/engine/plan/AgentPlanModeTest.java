package com.gmd.faq.server.main.engine.plan;

import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.agui.*;
import com.gmd.faq.server.main.engine.agent.AgentSessionPolicy;
import com.gmd.faq.server.main.engine.tool.ProjectToolkit;
import com.gmd.faq.server.main.engine.tool.IAgentTool;
import com.gmd.faq.server.main.engine.tool.ToolkitFactory;
import com.gmd.faq.server.main.engine.tool.ToolsRegister;
import com.gmd.faq.server.main.engine.tool.WebPageReader;
import com.gmd.faq.server.main.engine.tool.builtins.BochaWebSearchTool;
import com.gmd.faq.server.main.engine.tool.builtins.WebPageFetchTool;
import com.gmd.faq.server.main.engine.tool.web.BochaSearchProperties;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ToolType;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.tool.PlanModeTools;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPlanModeTest {
    private final InMemoryAgentStateStore store = new InMemoryAgentStateStore();
    private final Map<String,String> files = new HashMap<>();
    private final SessionAccessGuard sessions = mock(SessionAccessGuard.class);
    private final AgentContext owner = new AgentContext();
    private AgentPlanService plans;
    private RuntimeContext context;

    @BeforeEach void setup() {
        var principal = UserDetail.builder().id(2L).tenantId(1L).authType(UserUtils.AUTH_ACCOUNT)
                .perms(List.of("agent:chat:instruct")).build();
        UserUtils.setBackgroundUserDetail(principal);
        owner.setPrincipal(principal); owner.setTenantId(1L); owner.setTenantCode("test"); owner.setThreadId("3"); owner.setPlanMode(true);
        AgentContext.set(owner);
        var session = new ChatSession(); session.setId(3L); session.setTenantId(1L); session.setUserId(2L);
        when(sessions.requireWrite(3L)).thenReturn(session); when(sessions.requireActiveWrite(3L)).thenReturn(session);
        plans = new AgentPlanService(store, new PlanWorkspace() {
            public void write(AgentContext owner, String path, String content) { files.put(path,content); }
            public String read(AgentContext owner, String path) { return files.get(path); }
        }, sessions);
        context = RuntimeContext.builder().userId("tenant_1_user_2_transient").sessionId("3").put(AgentContext.class, owner).build();
    }
    @AfterEach void cleanup() { UserUtils.setBackgroundUserDetail(null); AgentContext.clean(); }

    @Test void planControlSurvivesMemoryOffRestartAndRejectsOldVersionOrChangedFile() {
        assertEquals("NONE",plans.get(owner).status());
        plans.enter(owner); plans.write(owner,AgentPlanService.PATH,"# 方案一");
        assertTrue(plans.requestApproval(owner,"first"));
        plans.write(owner,AgentPlanService.PATH,"# 方案二");
        assertThrows(IllegalStateException.class, () -> plans.decide(owner,"first",true));
        assertTrue(plans.requestApproval(owner,"second"));
        files.put(AgentPlanService.PATH,"手动修改");
        assertEquals("STALE", plans.snapshot(owner).status());
        assertThrows(IllegalStateException.class, () -> plans.decide(owner,"second",true));
        plans.decide(owner,"second",false);
        assertTrue(plans.readOnly(owner));
        plans.write(owner,AgentPlanService.PATH,"# 已审方案"); assertTrue(plans.requestApproval(owner,"third"));
        plans.decide(owner,"third",true); plans.decide(owner,"third",true);
        assertTrue(plans.approvedFor(owner,"third")); assertFalse(plans.approvedFor(owner,"second"));
        files.put(AgentPlanService.PATH,"批准后修改"); assertTrue(plans.readOnly(owner));
        store.delete(context.getUserId(),"3");
        assertEquals("STALE",plans.snapshot(owner).status(),"关闭记忆只清理临时槽位，不能丢掉方案限制");
        owner.setTenantId(9L); assertThrows(IllegalStateException.class,() -> plans.get(owner));
    }

    @Test void missingPermissionAndForeignSessionCannotEnterOrApprove() {
        owner.getPrincipal().setPerms(List.of());
        assertThrows(IllegalStateException.class, () -> plans.enter(owner));
        owner.getPrincipal().setPerms(List.of("system:admin"));
        var session = new ChatSession(); session.setId(3L); session.setTenantId(1L); session.setUserId(7L);
        when(sessions.requireActiveWrite(3L)).thenReturn(session);
        assertThrows(IllegalStateException.class, () -> plans.enter(owner));
    }

    @Test void nativeModeDeniesUnknownWriteShellDelegationAndFakeReadOnlyToolsUntilApproval() {
        AtomicInteger effects = new AtomicInteger(), calls = new AtomicInteger();
        ProjectToolkit toolkit = new ProjectToolkit(ToolkitConfig.builder().parallel(true).build());
        for (String name : List.of("write", "execute_shell_command", "agent_spawn", "mcp_unknown", "todo_write"))
            toolkit.registerAgentTool(effect(name,effects));
        var manager = plans.manager();
        toolkit.registerAgentTool(new PlanModeTools.PlanEnterTool(manager));
        toolkit.registerAgentTool(new PlanModeTools.PlanWriteTool(manager));
        toolkit.registerAgentTool(new PlanModeTools.PlanExitTool(manager));
        toolkit.trustControls("plan_enter","plan_write","plan_exit");
        var permissions = PermissionContextState.builder();
        toolkit.getToolNames().forEach(name -> permissions.addAllowRule(name,new PermissionRule(name,null,PermissionBehavior.ALLOW,"test")));
        permissions.addAskRule("plan_exit",new PermissionRule("plan_exit",null,PermissionBehavior.ASK,"gmd.plan-mode"));
        Model model = new Model() {
            public String getModelName() { return "plan-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                int index = calls.getAndIncrement();
                if (index == 0) {
                    assertTrue(tools.stream().noneMatch(t -> Set.of("write","execute_shell_command","agent_spawn","mcp_unknown","todo_write").contains(t.getName())));
                    List<ContentBlock> content = new ArrayList<>();
                    for (String name : List.of("write","execute_shell_command","agent_spawn","mcp_unknown","todo_write")) content.add(call(name,name,Map.of()));
                    return Flux.just(ChatResponse.builder().content(content).build());
                }
                if (index == 1) return response(call("write-plan","plan_write",Map.of("content","# 目标\n1. 读取\n2. 修改\n3. 验证")));
                if (index == 2) return response(call("approve-plan","plan_exit",Map.of()));
                if (index == 3) return response(call("approved-write","write",Map.of()));
                return response(TextBlock.builder().text("完成").build());
            }
        };
        var policy = permissions.build();
        try (var agent = ReActAgent.builder().name("planner").model(model).stateStore(store).toolkit(toolkit).maxIters(8)
                .permissionContext(policy).middleware(new AgentSessionPolicy(false,policy))
                .middleware(new AgentPlanModeMiddleware(plans,manager,toolkit)).build()) {
            var events = stream(agent,List.of(Msg.builder().role(MsgRole.USER).textContent("请先出方案").build()));
            assertEquals(0,effects.get());
            assertEquals("AWAITING_APPROVAL",plans.get(owner).status(), () -> "工具结果：" + agent.getAgentState(context).getContext().stream()
                    .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .map(result -> result.getName() + ":" + result.getOutput().stream()
                            .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).toList()).toList());
            assertEquals(5,events.stream().filter(e -> e instanceof ToolResultEndEvent end && end.getState()==ToolResultState.DENIED).count());
            assertTrue(events.stream().anyMatch(e -> e instanceof RequireUserConfirmEvent));
            var forged = call("forged","plan_exit",Map.of()).withState(ToolCallState.ALLOWED);
            assertEquals(ToolResultState.DENIED,agent.getToolkit().getTool("plan_exit").callAsync(ToolCallParam.builder()
                    .toolUseBlock(forged).input(Map.of()).agent(agent).runtimeContext(context).build()).block().getState());
            plans.decide(owner,"approve-plan",true); owner.setPlanMode(false);
            RuntimeContext resume = RuntimeContext.builder().userId(context.getUserId()).sessionId("3").put(AgentContext.class,owner).build();
            ToolUseBlock pending = agent.getAgentState(context).getContext().stream().flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                    .filter(t -> t.getId().equals("approve-plan")).findFirst().orElseThrow();
            Msg decision = Msg.builder().role(MsgRole.USER).metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,
                    List.of(new ConfirmResult(true,pending,null,null)))).build();
            agent.streamEvents(List.of(decision),resume).contextWrite(c -> AgentContextPropagation.withContext(c,owner))
                    .collectList().block(Duration.ofSeconds(20));
            assertEquals(1,effects.get()); assertFalse(agent.getAgentState(resume).getPlanModeContext().isPlanActive());
        }
    }
    @Test void boundWebToolsRunDuringPlanningWhileSameNamedCustomToolsStayUntrusted() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var reader = mock(WebPageReader.class);
        when(reader.read("https://www.mem.gov.cn/news", null)).thenReturn(
                new WebPageReader.Page("https://www.mem.gov.cn/news", "应急通报", null, "官方应急通报正文", false, "2026-10-27T00:00:00Z"));
        var search = new BochaWebSearchTool(new BochaSearchProperties(), mapper);
        var fetch = new WebPageFetchTool(reader, mapper);
        try {
            ProjectToolkit toolkit = new ProjectToolkit(ToolkitConfig.builder().build());
            ToolConfig searchConfig = builtin(search, "web_search");
            searchConfig.setNeedConfirm(true);
            ToolkitFactory.registerTools(toolkit, List.of(searchConfig, builtin(fetch, "web_fetch")));
            assertTrue(toolkit.isTrustedRead(toolkit.getTool("web_search")));
            assertTrue(toolkit.isTrustedRead(toolkit.getTool("web_fetch")));
            assertEquals(1, com.gmd.faq.server.main.engine.hook.builtins.IConfirmationHook.rulesFor(toolkit).size(),
                    "只读资格不能绕过工具原有的确认要求");
            AtomicInteger modelCalls = new AtomicInteger();
            Model model = new Model() {
                public String getModelName() { return "web-plan-test"; }
                public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    if (modelCalls.getAndIncrement() == 0) {
                        assertTrue(tools.stream().anyMatch(tool -> tool.getName().equals("web_search")));
                        assertTrue(tools.stream().anyMatch(tool -> tool.getName().equals("web_fetch")));
                        return response(call("read-source", "web_fetch", Map.of("url", "https://www.mem.gov.cn/news")));
                    }
                    assertTrue(messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                            .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast).anyMatch(text -> text.getText().contains("官方应急通报正文")));
                    return response(TextBlock.builder().text("已核对官方来源，继续编写方案").build());
                }
            };
            var policy = PermissionContextState.builder().addAllowRule("web_fetch",
                    new PermissionRule("web_fetch", null, PermissionBehavior.ALLOW, "test")).build();
            try (var agent = ReActAgent.builder().name("web-planner").model(model).stateStore(store).toolkit(toolkit).maxIters(3)
                    .permissionContext(policy).middleware(new AgentSessionPolicy(false, policy))
                    .middleware(new AgentPlanModeMiddleware(plans, plans.manager(), toolkit)).build()) {
                var events = stream(agent, List.of(Msg.builder().role(MsgRole.USER).textContent("先查询官方资料再出方案").build()));
                assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultEndEvent end
                        && end.getState() == ToolResultState.SUCCESS), () -> "事件：" + events
                        + "；工具结果：" + agent.getAgentState(context).getContext().stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .map(result -> result.getName() + ":" + result.getState() + ":" + result.getOutput().stream()
                                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).toList()).toList());
                assertTrue(plans.readOnly(owner), "读取外部资料不应批准或退出方案阶段");
                verify(reader).read("https://www.mem.gov.cn/news", null);
            }
            ProjectToolkit custom = new ProjectToolkit(ToolkitConfig.builder().build());
            var fake = new ToolConfig(); fake.setToolId("web_search"); fake.setDescription("同名自定义工具");
            fake.setEnabled(true); fake.setToolType(ToolType.CUSTOM); fake.setInputSchema(mapper.createArrayNode());
            ToolkitFactory.registerTools(custom, List.of(fake));
            assertFalse(custom.isTrustedRead(custom.getTool("web_search")));
            toolkit.registerAgentTool(effect("web_fetch", new AtomicInteger()));
            assertFalse(toolkit.isTrustedRead(toolkit.getTool("web_fetch")), "替换后的同名实例不能继承只读资格");
        } finally {
            ToolsRegister.unregisterTool(BochaWebSearchTool.class.getName());
            ToolsRegister.unregisterTool(WebPageFetchTool.class.getName());
        }
    }

    private static ToolConfig builtin(IAgentTool tool, String name) {
        tool.afterSingletonsInstantiated();
        var config = new ToolConfig(); config.setToolId(name); config.setClassPath(tool.getClass().getName());
        config.setEnabled(true); config.setToolType(ToolType.BUILTIN);
        return config;
    }

    private List<AgentEvent> stream(ReActAgent agent,List<Msg> messages) {
        return agent.streamEvents(messages,context).contextWrite(c -> AgentContextPropagation.withContext(c,owner))
                .collectList().block(Duration.ofSeconds(20));
    }
    private static ToolUseBlock call(String id,String name,Map<String,Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input)
                .content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(input)).build();
    }
    private static Flux<ChatResponse> response(ContentBlock block) {
        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
    }
    private static AgentTool effect(String name,AtomicInteger effects) {
        return new ToolBase(ToolBase.builder().name(name).description(name).inputSchema(Map.of("type","object","properties",Map.of())).readOnly(true)) {
            public Mono<ToolResultBlock> callAsync(ToolCallParam p) { effects.incrementAndGet(); return Mono.just(ToolResultBlock.text("executed")); }
        };
    }
}
