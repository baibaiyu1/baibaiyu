package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.agent.service.AgentTelemetryService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.*;
import com.gmd.faq.server.main.engine.telemetry.*;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResilientModelTest {
    private final AgentTelemetryService store = mock(AgentTelemetryService.class);
    private final AgentTelemetry telemetry = new AgentTelemetry(store);
    private final AgentContext owner = new AgentContext();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static class HttpFailure extends ModelException implements ModelHttpException {
        private final int code;
        HttpFailure(int code) { super("正文密钥不应落观测"); this.code = code; }
        public Integer getStatusCode() { return code; }
    }
    public static class SideEffect {
        final AtomicInteger count = new AtomicInteger();
        @Tool(name = "side_effect", description = "副作用计数") public String write() { count.incrementAndGet(); return "written"; }
    }
    @BeforeEach void setup() {
        owner.setTenantId(1L); owner.setThreadId("3"); owner.setRunId("run");
        AccountVO user = new AccountVO(); user.setId(2L); owner.setUserInfo(user);
        AgentDefinition definition = new AgentDefinition(); definition.setId(4L); owner.setAgentDefinition(definition);
        AgentContext.set(owner);
        when(store.begin(any(),anyString(),nullable(String.class))).thenAnswer(i -> UUID.randomUUID().toString());
    }
    @AfterEach void cleanup() { AgentContext.clean(); }
    private Model model(String name, Supplier<Flux<ChatResponse>> replies) {
        return new Model() {
            public String getModelName() { return name; }
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                assertEquals(1, options.getExecutionConfig().getMaxAttempts());
                return replies.get();
            }
        };
    }
    private ChatResponse reply(String text) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build()))
                .usage(ChatUsage.builder().inputTokens(10).outputTokens(3).build()).build();
    }

    @Test void retriesThenFallbackDoNotRepeatToolSideEffectsAndExposeActualModelUsage() {
        AtomicInteger primaryCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
        var primary = model("primary", () -> primaryCalls.getAndIncrement() == 0 ? Flux.just(ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder().id("write-once").name("side_effect").input(Map.of()).content("{}").build()))
                .usage(ChatUsage.builder().inputTokens(7).outputTokens(2).build()).build()) : Flux.error(new HttpFailure(429)));
        var fallback = model("fallback", () -> { fallbackCalls.incrementAndGet(); return Flux.just(reply("完成")); });
        var resilient = new ResilientModel(telemetry.observe(primary,4,"MODEL"),telemetry.observe(fallback,4,"MODEL"),3,telemetry,4);
        SideEffect sideEffect = new SideEffect(); Toolkit toolkit = new Toolkit(); toolkit.registerTool(sideEffect);
        var rc = RuntimeContext.builder().userId("u").sessionId("3").put(AgentContext.class,owner).build();
        try (var agent = ReActAgent.builder().name("retry").model(resilient).toolkit(toolkit).maxIters(4)
                .middleware(new AgentTelemetryMiddleware(telemetry,4,true)).build()) {
            var events = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("写一次").build()),rc)
                    .contextWrite(c -> AgentContextPropagation.withContext(c,owner)).collectList().block(TIMEOUT);
            assertEquals(4,primaryCalls.get()); assertEquals(1,fallbackCalls.get()); assertEquals(1,sideEffect.count.get());
            assertEquals(2,events.stream().filter(e -> e instanceof CustomEvent c && c.getName().equals("MODEL_RETRY")).count());
            var usage = (CustomEvent)events.stream().filter(e -> e instanceof CustomEvent c && c.getName().equals("MODEL_USAGE"))
                    .reduce((first,last) -> last).orElseThrow();
            assertEquals("fallback",usage.getValue().get("model"));
            verify(store,times(4)).begin(any(),eq("MODEL"),eq("primary"));
            verify(store).begin(any(),eq("MODEL"),eq("fallback"));
            verify(store,times(2)).begin(any(),eq("MODEL_RETRY"),eq("primary"));
            verify(store).begin(any(),eq("MODEL_SWITCH"),eq("fallback"));
        }
    }

    @Test void errorsAfterFirstChunkCannotRestartOrSwitchModels() {
        AtomicInteger calls = new AtomicInteger();
        var primary = model("partial", () -> { calls.incrementAndGet(); return Flux.concat(Flux.just(reply("部分内容")),Flux.error(new HttpFailure(503))); });
        var fallback = model("fallback", () -> fail("部分输出后不得切换"));
        var resilient = new ResilientModel(primary,fallback,5,telemetry,4);
        List<String> output = new ArrayList<>();
        assertThrows(HttpFailure.class, () -> resilient.stream(List.of(),List.of(),null).doOnNext(r -> output.add(((TextBlock)r.getContent().getFirst()).getText())).blockLast(TIMEOUT));
        assertEquals(List.of("部分内容"),output); assertEquals(1,calls.get()); verifyNoInteractions(store);
    }

    @Test void overlappingSessionsKeepTheirOwnActualModelInUsageEvents() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var fallbackStarted = new java.util.concurrent.CountDownLatch(1);
        var fallbackReply = reactor.core.publisher.Sinks.<ChatResponse>one();
        Model primary = model("primary", () -> calls.getAndIncrement() == 0
                ? Flux.error(new HttpFailure(503)) : Flux.just(reply("主模型回答")));
        Model fallback = model("fallback", () -> {
            fallbackStarted.countDown();
            return fallbackReply.asMono().flux();
        });
        var resilient = new ResilientModel(primary, fallback, 1, telemetry, 4);
        try (var agent = ReActAgent.builder().name("shared").model(resilient)
                .middleware(new AgentTelemetryMiddleware(telemetry,4,true)).build()) {
            var firstContext = RuntimeContext.builder().userId("u").sessionId("first").put(AgentContext.class,owner).build();
            var secondContext = RuntimeContext.builder().userId("u").sessionId("second").put(AgentContext.class,owner).build();
            var message = List.of(Msg.builder().role(MsgRole.USER).textContent("回答").build());
            var first = agent.streamEvents(message,firstContext).collectList().toFuture();
            assertTrue(fallbackStarted.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var second = agent.streamEvents(message,secondContext).collectList().block(TIMEOUT);
            fallbackReply.tryEmitValue(reply("备用模型回答"));
            var firstEvents = first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("primary", second.stream().filter(e -> e instanceof CustomEvent c && c.getName().equals("MODEL_USAGE"))
                    .map(e -> ((CustomEvent)e).getValue().get("model")).findFirst().orElseThrow());
            assertEquals("fallback", firstEvents.stream().filter(e -> e instanceof CustomEvent c && c.getName().equals("MODEL_USAGE"))
                    .map(e -> ((CustomEvent)e).getValue().get("model")).findFirst().orElseThrow());
        }
    }

    @Test void badCredentialsAndParameterErrorsDoNotRetryOrFallback() {
        AtomicInteger calls = new AtomicInteger();
        var primary = model("unauthorized", () -> { calls.incrementAndGet(); return Flux.error(new HttpFailure(401)); });
        var fallback = model("fallback", () -> fail("配置错误不得切换"));
        assertThrows(HttpFailure.class, () -> new ResilientModel(primary,fallback,3,telemetry,4).stream(List.of(),List.of(),null).blockLast(TIMEOUT));
        assertEquals(1,calls.get()); verifyNoInteractions(store);
    }

    @Test void cancellationDuringBackoffDoesNotStartAnotherAttempt() {
        AtomicInteger calls = new AtomicInteger();
        var primary = model("cancel", () -> { calls.incrementAndGet(); return Flux.error(new HttpFailure(429)); });
        var resilient = new ResilientModel(primary,null,5,telemetry,4);
        resilient.stream(List.of(),List.of(),null).takeUntilOther(Mono.delay(Duration.ofMillis(50))).blockLast(TIMEOUT);
        assertEquals(1,calls.get()); verifyNoInteractions(store);
    }

    @Test void responsesTransportFailureWithoutHttpStatusStillRetriesAndFallsBack() {
        var failure = new io.agentscope.extensions.model.openaiofficial.OpenAIOfficialModelException(
                "transport failed", new java.io.IOException("offline"), "primary");
        AtomicInteger attempts = new AtomicInteger(), backups = new AtomicInteger();
        var primary = model("primary", () -> { attempts.incrementAndGet(); return Flux.error(failure); });
        var fallback = model("fallback", () -> { backups.incrementAndGet(); return Flux.just(reply("备用答复")); });
        var result = new ResilientModel(primary, fallback, 2, telemetry, 4).stream(List.of(), List.of(), null).blockLast(TIMEOUT);
        assertNotNull(result); assertEquals("备用答复", ((TextBlock) result.getContent().getFirst()).getText());
        assertEquals(2, attempts.get()); assertEquals(1, backups.get());
    }

    @Test void explicitAuthenticationFailureTakesPrecedenceOverAWrappedIoCause() {
        var failure = new io.agentscope.extensions.model.openaiofficial.OpenAIOfficialModelException(
                "unauthorized", new java.io.IOException("nested transport"), "primary", 401);
        AtomicInteger calls = new AtomicInteger();
        var primary = model("primary", () -> { calls.incrementAndGet(); return Flux.error(failure); });
        var fallback = model("fallback", () -> fail("鉴权失败不得切换"));
        assertThrows(failure.getClass(), () -> new ResilientModel(primary, fallback, 3, telemetry, 4)
                .stream(List.of(), List.of(), null).blockLast(TIMEOUT));
        assertEquals(1, calls.get()); verifyNoInteractions(store);
    }
}
