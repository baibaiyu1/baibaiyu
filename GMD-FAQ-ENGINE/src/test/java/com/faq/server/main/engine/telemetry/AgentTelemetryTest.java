package com.gmd.faq.server.main.engine.telemetry;

import com.gmd.faq.server.main.agent.service.AgentTelemetryService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.*;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTelemetryTest {
    private final AgentTelemetryService store=mock(AgentTelemetryService.class);
    private final AgentTelemetry telemetry=new AgentTelemetry(store);
    private final AgentContext owner=new AgentContext();
    @BeforeEach void setup() {
        owner.setTenantId(1L);owner.setThreadId("3");owner.setRunId("run");
        AccountVO account=new AccountVO();account.setId(2L);owner.setUserInfo(account);
        AgentDefinition definition=new AgentDefinition();definition.setId(4L);owner.setAgentDefinition(definition);
        AgentContext.set(owner);
        when(store.begin(any(),anyString(),nullable(String.class))).thenAnswer(i -> UUID.randomUUID().toString());
    }
    @AfterEach void cleanup() { AgentContext.clean(); }
    private Model model(Flux<ChatResponse> replies) {
        return new Model() {
            public String getModelName() { return "usage-model"; }
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) { return replies; }
            public boolean supportsNativeStructuredOutput() { return true; }
        };
    }
    private ChatResponse response(int input,int output) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text("答案").build()))
                .usage(ChatUsage.builder().inputTokens(input).outputTokens(output).reasoningTokens(2).build()).build();
    }
    @Test void cumulativeSnapshotsCountOnceAndCompactionHasSeparateIdentity() {
        Model raw=model(Flux.just(response(10,3),response(10,7)));
        Model observed=telemetry.observe(raw,4,"MODEL");
        assertTrue(observed.supportsNativeStructuredOutput());
        observed.stream(List.of(),List.of(),null).contextWrite(c -> AgentContextPropagation.withContext(c,owner)).collectList().block(Duration.ofSeconds(5));
        telemetry.compaction(observed,4).stream(List.of(),List.of(),null)
                .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).collectList().block(Duration.ofSeconds(5));
        var usage=ArgumentCaptor.forClass(AgentTelemetryService.Usage.class);
        verify(store,times(2)).end(anyString(),eq("SUCCEEDED"),anyLong(),usage.capture(),isNull(),isNull());
        assertEquals(List.of(7L,7L),usage.getAllValues().stream().map(AgentTelemetryService.Usage::output).toList());
        verify(store).begin(new AgentTelemetryService.Scope(1,2,3,"run",4),"MODEL","usage-model");
        verify(store).begin(new AgentTelemetryService.Scope(1,2,3,"run",4),"COMPACTION","usage-model");
    }
    @Test void missingIdentityNeverWritesAndStorageFailuresDoNotFailModel() {
        AgentContext.clean();
        telemetry.observe(model(Flux.just(response(1,2))),4,"MODEL").stream(List.of(),List.of(),null).blockLast(Duration.ofSeconds(5));
        verifyNoInteractions(store);
        when(store.begin(any(),anyString(),anyString())).thenThrow(new IllegalStateException("secret-value"));
        assertNotNull(telemetry.observe(model(Flux.just(response(1,2))),4,"MODEL").stream(List.of(),List.of(),null)
                .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).blockLast(Duration.ofSeconds(5)));
    }
    @Test void failureAndCancellationRetainUnknownUsage() throws Exception {
        assertThrows(IllegalStateException.class,() -> telemetry.observe(model(Flux.error(new IllegalStateException("secret"))),4,"MODEL")
                .stream(List.of(),List.of(),null).contextWrite(c -> AgentContextPropagation.withContext(c,owner)).blockLast(Duration.ofSeconds(5)));
        verify(store).end(anyString(),eq("FAILED"),anyLong(),isNull(),eq("MODEL"),any(IllegalStateException.class));
        CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        when(store.begin(any(),eq("MODEL"),anyString())).thenAnswer(i -> { started.countDown();return "cancelled-call"; });
        doAnswer(i -> {cancelled.countDown();return null;}).when(store).end(eq("cancelled-call"),eq("CANCELLED"),anyLong(),isNull(),eq("MODEL"),isNull());
        var subscription=telemetry.observe(model(Flux.never()),4,"MODEL").stream(List.of(),List.of(),null)
                .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).subscribe();
        assertTrue(started.await(5,TimeUnit.SECONDS));subscription.dispose();assertTrue(cancelled.await(5,TimeUnit.SECONDS));
    }
    @Test void nativeMiddlewareEmitsActualUsageWithoutExtraModelCalls() {
        Model observed=telemetry.observe(model(Flux.just(response(11,5))),4,"MODEL");
        var rc=RuntimeContext.builder().userId("u").sessionId("3").put(AgentContext.class,owner).build();
        try(var agent=ReActAgent.builder().name("usage").model(observed).middleware(new AgentTelemetryMiddleware(telemetry,4,true)).build()) {
            var events=agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("运行").build()),rc)
                    .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).collectList().block(Duration.ofSeconds(10));
            var event=(CustomEvent) events.stream().filter(e -> e instanceof CustomEvent custom && "MODEL_USAGE".equals(custom.getName())).findFirst().orElseThrow();
            assertEquals(11,event.getValue().get("inputTokens"));assertEquals(5,event.getValue().get("outputTokens"));
            verify(store).begin(any(),eq("MODEL"),eq("usage-model"));verify(store).begin(any(),eq("RUN"),isNull());
        }
    }
    @Test void nativeUsageEventDoesNotReportSdkDefaultZeroAsRealProviderUsage() {
        var rc=RuntimeContext.builder().userId("u").sessionId("3").put(AgentContext.class,owner).build();
        try(var agent=ReActAgent.builder().name("unknown").model(model(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("没有 usage").build())).build())))
                .middleware(new AgentTelemetryMiddleware(telemetry,4,true)).build()) {
            var events=agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("运行").build()),rc)
                    .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).collectList().block(Duration.ofSeconds(10));
            var event=(CustomEvent) events.stream().filter(e -> e instanceof CustomEvent custom && "MODEL_USAGE".equals(custom.getName())).findFirst().orElseThrow();
            assertEquals(false,event.getValue().get("available"));assertFalse(event.getValue().containsKey("inputTokens"));
        }
    }
}
