package com.gmd.faq.server.main.runtime.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.util.*;
import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import com.gmd.faq.server.main.engine.formatter.ToolResultImageModel;
import com.gmd.faq.server.main.engine.knowledge.*;
import com.gmd.faq.server.main.engine.log.*;
import com.gmd.faq.server.main.engine.tool.*;
import com.gmd.faq.server.main.engine.workspace.hook.ToolResultImageHook;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.*;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.*;
import io.agentscope.core.tool.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalStreamTest {
    @ParameterizedTest @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void nativeKnowledgeRetainsCitationsAndVisionInStreamAndHistory(boolean automatic, boolean withImage,
            @TempDir Path workspace) throws Exception {
        var saved = new CopyOnWriteArrayList<ChatMessage>();
        var retrieved = new AtomicInteger();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String base64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
        var payload = new HashMap<String, Object>(Map.of("knowledgeId", "42", "knowledgeName", "设备库",
                "file_path", "设备手册.pdf", "reference_id", "1"));
        if (withImage) payload.put("images", List.of(Map.of("data", base64, "mime_type", "image/png", "caption", "设备示意图")));
        var document = new Document(DocumentMetadata.builder().content(TextBlock.builder().text("使用前检查电源").build())
                .docId("doc").chunkId("chunk").payload(payload).build());
        Knowledge kb = new Knowledge() {
            public Mono<Void> addDocuments(List<Document> docs) { return Mono.empty(); }
            public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                assertEquals(9, config.getLimit());
                retrieved.incrementAndGet(); return Mono.just(List.of(document));
            }
        };
        var config = RetrieveConfig.builder().limit(9).build();
        var retrieval = new KnowledgeRetrievalTool(List.of(KnowledgeWrapper.builder().knowledge(kb).build()), config);
        Toolkit toolkit = new Toolkit();
        if (!automatic) toolkit.registerTool(retrieval);
        var modelCalls = new AtomicInteger();
        var modelInputs = new ArrayList<List<Msg>>();
        Model model = new Model() {
            public String getModelName() { return "knowledge-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                modelInputs.add(List.copyOf(messages));
                int call = modelCalls.incrementAndGet();
                return Flux.just(ChatResponse.builder().content(!automatic && call == 1
                        ? List.of(ToolUseBlock.builder().id("retrieve").name(KnowledgeRetrievalTool.NAME)
                                .content("{\"query\":\"使用方法\"}").input(Map.of("query", "使用方法")).build())
                        : List.of(TextBlock.builder().text("按手册使用").build())).build());
            }
        };
        var imageHook = new ToolResultImageHook(new ToolResultImageSupport() {
            public ToolResultBlock normalize(ToolResultBlock result, Path ignored) { return super.normalize(result, workspace); }
        });
        var chatLog = new ChatLogHook();
        Hook capture = new Hook() {
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                return imageHook.onEvent(event).doOnNext(completed -> {
                    try (var logs = mockStatic(ConcurrentLogProducer.class)) {
                        logs.when(() -> ConcurrentLogProducer.pushLog(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return null; });
                        chatLog.onEvent(completed);
                    }
                });
            }
        };
        Object oldMapper = ReflectionTestUtils.getField(JsonUtils.class, "objectMapper");
        ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", new ObjectMapper().findAndRegisterModules());
        try (var beans = mockStatic(BeanUtils.class)) {
            beans.when(() -> BeanUtils.getBean(AttachService.class)).thenReturn(mock(AttachService.class));
            var builder = ReActAgent.builder().name("kb").model(new ToolResultImageModel(model)).toolkit(toolkit)
                    .maxIters(3).hooks(List.of(capture));
            if (automatic) builder.middleware(new KnowledgeRetrievalMiddleware(retrieval, config));
            try (var agent = builder.build()) {
                AgentMetadataStore.put(agent.getAgentId(), "tenantId", 1L);
                AgentMetadataStore.put(agent.getAgentId(), "tenantCode", "test");
                AgentMetadataStore.put(agent.getAgentId(), "threadId", "123");
                AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", false);
                var events = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                        .runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("设备使用方法").build()), "123", "run")
                        .collectList().block(Duration.ofSeconds(15));
                assertNotNull(events);
                assertTrue(events.stream().noneMatch(AguiEvent.RunError.class::isInstance), events::toString);
                assertEquals(1, retrieved.get(), "一个问题只检索一次");
                assertEquals(automatic ? 1 : 2, modelCalls.get(), "自动检索不增加决策模型调用");
                var cards = events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                        .filter(e -> ToolResultPresentation.EVENT_NAME.equals(e.name())).toList();
                assertEquals(1, cards.size(), events::toString);
                if (automatic) {
                    var progress = events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                            .filter(e -> KnowledgeRetrievalMiddleware.EVENT_NAME.equals(e.name())).toList();
                    assertEquals(List.of("STARTED", "FINISHED"), progress.stream()
                            .map(e -> ((Map<?, ?>) e.value()).get("status")).toList());
                    assertEquals(((Map<?, ?>) progress.getFirst().value()).get("toolCallId"),
                            ((Map<?, ?>) progress.getLast().value()).get("toolCallId"));
                    assertTrue(events.indexOf(progress.getFirst()) < events.indexOf(cards.getFirst()));
                    assertTrue(events.indexOf(progress.getLast()) > events.indexOf(cards.getFirst()));
                } else {
                    assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart start
                            && KnowledgeRetrievalTool.NAME.equals(start.toolCallName())), "按需检索必须发布原生工具开始事件");
                }
                Map<?, ?> value = (Map<?, ?>)cards.getFirst().value();
                assertEquals(1, ((List<?>)value.get("citations")).size());
                assertEquals(withImage ? 1 : 0, ((List<?>)value.get("images")).size());
                assertFalse(String.valueOf(value.get("text")).contains(base64));
                var last = modelInputs.getLast();
                assertTrue(last.stream().anyMatch(m -> m.getTextContent().contains("使用前检查电源")
                        || m.getContentBlocks(ToolResultBlock.class).stream().flatMap(r -> r.getOutput().stream())
                                .anyMatch(b -> b instanceof TextBlock t && t.getText().contains("使用前检查电源"))));
                assertEquals(withImage, last.stream().anyMatch(m -> !m.getContentBlocks(ImageBlock.class).isEmpty()));
                assertEquals(1, saved.stream().filter(m -> m.getRole().equals("tool") && m.getContent().contains("citations")
                        && m.getContent().contains("设备手册.pdf") && m.getContent().contains("\"hideDetails\":true")).count());
                if (automatic) {
                    assertEquals(1, agent.getAgentState(null, "123").getContext().stream().filter(m -> "retrieved_knowledge".equals(m.getName())).count());
                    var adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
                    adapter.runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("再查安全事项").build()), "123", "next")
                            .collectList().block(Duration.ofSeconds(15));
                    assertEquals(2, retrieved.get(), "新提问重新检索，不能复用上一轮的完成标记");
                    assertEquals(2, modelCalls.get());
                    assertEquals(2, agent.getAgentState(null, "123").getContext().stream().filter(m -> "retrieved_knowledge".equals(m.getName())).count());
                    adapter.runWithMessages(List.of(), "123", "resume")
                            .collectList().block(Duration.ofSeconds(15));
                    assertEquals(2, retrieved.get(), "没有新用户文本的续跑不能重新检索旧问题");
                }
                AgentMetadataStore.removeOnOwn(agent.getAgentId());
            }
        } finally { ReflectionTestUtils.setField(JsonUtils.class, "objectMapper", oldMapper); }
    }
}
