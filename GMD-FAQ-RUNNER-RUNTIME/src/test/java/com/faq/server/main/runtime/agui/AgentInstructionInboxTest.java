package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentInstructionService;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agent.AgentInstructionMiddleware;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** 独立临时库中验证收件事务和真实 ReActAgent 推理边界。 */
@EnabledIfEnvironmentVariable(named="AGENTSCOPE_TEST_MYSQL_URL", matches=".*/gmd_agentscope_upgrade_check.*")
class AgentInstructionInboxTest {
    private JdbcTemplate jdbc;
    private AgentInstructionService inbox;
    private final AgentInstructionService.Scope scope = new AgentInstructionService.Scope(71,72,73,"instruction-run");

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(System.getenv("AGENTSCOPE_TEST_MYSQL_URL"),
                System.getenv("AGENTSCOPE_TEST_MYSQL_USER"), System.getenv("AGENTSCOPE_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM " + TableConst.AGENT_INSTRUCTION);
        jdbc.update("DELETE FROM " + TableConst.AGENT_INSTRUCTION_RUN);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new AgentInstructionService(jdbc)); proxy.setProxyTargetClass(true);
        proxy.setTransactionManager(new DataSourceTransactionManager(source));
        Properties attributes = new Properties(); attributes.setProperty("*", "PROPAGATION_REQUIRED");
        proxy.setTransactionAttributes(attributes); proxy.afterPropertiesSet();
        inbox = (AgentInstructionService) proxy.getObject();
    }

    @Test void retriesAreIdempotentAndScopesCannotClaimEachOthersInstructions() {
        inbox.begin(scope,74);
        var first = inbox.receive(scope,"one","优先最近一个月");
        assertEquals(first, inbox.receive(scope,"one","优先最近一个月"));
        assertThrows(IllegalArgumentException.class, () -> inbox.receive(scope,"one","不同内容"));
        inbox.receive(scope,"two","再按地区分组");
        assertTrue(inbox.claim(new AgentInstructionService.Scope(75,72,73,"instruction-run")).isEmpty());
        assertTrue(inbox.list(71,75,73).isEmpty());
        var claimed = inbox.claim(scope);
        assertEquals(List.of("one","two"), claimed.stream().map(AgentInstructionService.Instruction::requestId).toList());
        assertTrue(inbox.claim(scope).isEmpty());
        inbox.applied(scope, List.of(claimed.getFirst()));
        inbox.receive(scope,"three","最后汇总");
        inbox.finish(scope,"STOPPED");
        var messages = inbox.list(71,72,73);
        assertEquals(List.of("CANCELLED","UNCONFIRMED","APPLIED"), messages.stream().map(AgentInstructionService.Instruction::state).toList());
        assertFalse(inbox.isActive(scope));
        assertThrows(IllegalStateException.class, () -> inbox.receive(scope,"four","旧轮不能重投"));
        inbox.clearSession(71,72,73);
        assertTrue(inbox.list(71,72,73).isEmpty());
    }

    @Test void boundedInboxExpiresWithoutReplayingUnconfirmedMessages() {
        inbox.begin(scope,74);
        for (int i=0;i<50;i++) inbox.receive(scope,"request-"+i,"补充"+i);
        assertThrows(IllegalStateException.class, () -> inbox.receive(scope,"overflow","超过上限"));
        inbox.claim(scope);
        jdbc.update("UPDATE " + TableConst.AGENT_INSTRUCTION + " SET created_at=DATE_SUB(NOW(), INTERVAL 25 HOUR)");
        assertTrue(inbox.list(71,72,73).stream().allMatch(m -> "UNCONFIRMED".equals(m.state())));
        assertTrue(inbox.claim(scope).isEmpty());
    }

    @Test void instructionsReceivedDuringModelAndToolAppearOnceInNextReasoning() {
        var calls = new AtomicInteger(); var toolsCalled = new AtomicInteger();
        Model model = new Model() {
            public String getModelName() { return "inbox-fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    inbox.receive(scope,"model","模型调用中补充");
                    return Flux.just(ChatResponse.builder().content(List.of(new ToolUseBlock("tool-call","pause",Map.of()))).build());
                }
                var added = messages.stream().filter(m -> m.getId().startsWith("instruction-")).toList();
                assertEquals(List.of("用户对当前任务的补充要求：\n模型调用中补充","用户对当前任务的补充要求：\n工具执行中补充"),
                        added.stream().map(Msg::getTextContent).toList());
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("已按要求完成").build())).build());
            }
        };
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            public String getName() { return "pause"; }
            public String getDescription() { return "投递测试"; }
            public Map<String,Object> getParameters() { return Map.of("type","object","properties",Map.of()); }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                toolsCalled.incrementAndGet(); inbox.receive(scope,"tool","工具执行中补充");
                return Mono.just(ToolResultBlock.of(TextBlock.builder().text("工具完成").build()));
            }
        });
        AgentContext owner = new AgentContext(); owner.setTenantId(71L); owner.setThreadId("73"); owner.setRunId(scope.runId());
        AccountVO account = new AccountVO(); account.setId(72L); owner.setUserInfo(account);
        AgentDefinition definition = new AgentDefinition(); definition.setId(74L); owner.setAgentDefinition(definition);
        var context = RuntimeContext.builder().userId("test").sessionId("73").put(AgentContext.class,owner).build();
        try (var agent = ReActAgent.builder().name("instructions").model(model).toolkit(toolkit).stateStore(new InMemoryAgentStateStore())
                .middleware(new AgentInstructionMiddleware(inbox)).maxIters(3).build()) {
            agent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("执行任务").build()),context).block(Duration.ofSeconds(15));
            assertEquals(2,calls.get()); assertEquals(1,toolsCalled.get());
            assertEquals(2,agent.getAgentState(context).getContext().stream().filter(m -> m.getId().startsWith("instruction-")).count());
            assertTrue(inbox.list(71,72,73).stream().allMatch(m -> "APPLIED".equals(m.state())));
        }
    }
}
