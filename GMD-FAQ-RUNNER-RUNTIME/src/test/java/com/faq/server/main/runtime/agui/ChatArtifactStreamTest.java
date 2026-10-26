package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.ChatArtifactService;
import com.gmd.faq.server.main.common.entity.ChatArtifact;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.ws.WebSocketClient;
import com.gmd.faq.server.main.engine.log.ChatLogHook;
import com.gmd.faq.server.main.engine.log.ConcurrentLogProducer;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.engine.workspace.hook.ArtifactHook;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.hook.*;
import com.gmd.faq.server.main.common.entity.ChatMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.test.util.ReflectionTestUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatArtifactStreamTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void generatedFilesRemainBoundToTheirToolInStreamAndHistoryWithDetailsHidden(boolean direct, @TempDir Path root) throws Exception {
        Object previousMapper = ReflectionTestUtils.getField(JsonUtils.class, "objectMapper");
        ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", new ObjectMapper().findAndRegisterModules());
        var artifacts = mock(ChatArtifactService.class);
        var notifications = mock(WebSocketClient.class);
        var calls = new AtomicInteger();
        var savedMessages = new CopyOnWriteArrayList<ChatMessage>();
        var artifactHook = new ArtifactHook(artifacts, notifications);
        var chatLog = new ChatLogHook();
        // SDK 的后置 Hook 在工具完成线程执行，静态替身需在该线程内建立。
        Hook capture = new Hook() {
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                Mono<T> enriched;
                try (var paths = mockStatic(com.gmd.faq.server.main.common.consts.SysConst.class)) {
                    paths.when(() -> com.gmd.faq.server.main.common.consts.SysConst.getWorkspacePath("test")).thenReturn(root.toString());
                    enriched = artifactHook.onEvent(event);
                }
                return enriched.doOnNext(value -> {
                    try (var logs = mockStatic(ConcurrentLogProducer.class)) {
                        logs.when(() -> ConcurrentLogProducer.pushLog(any())).thenAnswer(invocation -> {
                            savedMessages.add(invocation.getArgument(0)); return null;
                        });
                        chatLog.onEvent(value);
                    }
                });
            }
        };
        Model model = new Model() {
            public String getModelName() { return "artifact-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(calls.getAndIncrement() == 0
                        ? List.of(ToolUseBlock.builder().id("plot-call").name("execute_shell_command").input(Map.of()).build())
                        : List.of(TextBlock.builder().text("图表说明").build())).build());
            }
        };
        try (var beans = mockStatic(BeanUtils.class)) {
            beans.when(() -> BeanUtils.getBean(AttachService.class)).thenReturn(mock(AttachService.class));
            Path workspace = Files.createDirectories(root.resolve("123"));
            var toolkit = new Toolkit();
            toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name("execute_shell_command")
                    .description("生成图表").returnDirect(direct).inputSchema(Map.of("type", "object", "properties", Map.of()))) {
                public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                    return Mono.fromCallable(() -> {
                        Files.writeString(workspace.resolve("趋势图.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
                        return ToolResultBlock.of(List.of(TextBlock.builder().text("完成").build()));
                    });
                }
            });
            try (var agent = ReActAgent.builder().name("artifacts").model(model).toolkit(toolkit).maxIters(3)
                    .hooks(List.of(capture)).build()) {
                AgentMetadataStore.put(agent.getAgentId(), "tenantCode", "test");
                AgentMetadataStore.put(agent.getAgentId(), "tenantId", 1L);
                AgentMetadataStore.put(agent.getAgentId(), "threadId", "123");
                AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", false);
                var events = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("生成图表").build()), "123", "run")
                        .collectList().block(Duration.ofSeconds(15));
                assertNotNull(events);
                assertTrue(events.stream().noneMatch(AguiEvent.RunError.class::isInstance), () -> events.toString());
                var custom = events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                        .filter(event -> ToolResultPresentation.EVENT_NAME.equals(event.name())).findFirst().orElseThrow();
                Map<?, ?> value = (Map<?, ?>) custom.value();
                assertEquals("plot-call", value.get("toolCallId"));
                var files = (List<ToolResultPresentation.Artifact>) value.get("artifacts");
                assertEquals("趋势图.svg", files.getFirst().filePath());
                var saved = ArgumentCaptor.forClass(ChatArtifact.class);
                verify(artifacts).recordArtifact(saved.capture());
                assertEquals(123L, saved.getValue().getSessionId());
                assertTrue(savedMessages.stream().anyMatch(message -> message.getRole().equals("tool")
                        && message.getContent().contains("plot-call") && message.getContent().contains("趋势图.svg")
                        && message.getContent().contains("\"hideDetails\":true")));
                AgentMetadataStore.removeOnOwn(agent.getAgentId());
            }
        } finally { ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", previousMapper); }
    }
}
