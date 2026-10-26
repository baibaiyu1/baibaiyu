package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.engine.formatter.ToolResultImageModel;
import com.gmd.faq.server.main.engine.tool.ToolResultImageSupport;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.engine.tool.PermissionAwareTool;
import com.gmd.faq.server.main.engine.workspace.hook.ToolResultImageHook;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolBase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 真正运行 ReAct 工具循环，验证 hook、模型视觉输入和 AG-UI 事件协同工作。 */
public class ToolResultImageStreamTest {
    @Test void directImageUsesTheActualToolResultAndRemainsVisibleInTheNextTurn(@TempDir Path workspace) throws Exception {
        try (var beans = Mockito.mockStatic(BeanUtils.class)) {
            beans.when(() -> BeanUtils.getBean(AttachService.class)).thenReturn(Mockito.mock(AttachService.class));
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            var actual = new ToolResultImageSupport().normalize(ToolResultBlock.of(List.of(
                    TextBlock.builder().text("直接图片答复").build(),
                    new ImageBlock(new Base64Source("image/png", Base64.getEncoder().encodeToString(bytes.toByteArray()))))), workspace);
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(new PermissionAwareTool(new ToolBase(ToolBase.builder().name("screenshot")
                    .description("返回截图").inputSchema(Map.of("type", "object", "properties", Map.of())).returnDirect(true)) {
                @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return Mono.just(actual); }
            }));
            RecordingModel model = new RecordingModel();
            try (ReActAgent agent = ReActAgent.builder().name("direct-image").model(new ToolResultImageModel(model))
                    .toolkit(toolkit).maxIters(3).build()) {
                AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", false);
                var adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
                var events = adapter.runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("获取截图").build()), "direct-session", "first")
                        .collectList().block(Duration.ofSeconds(15));
                assertNotNull(events);
                assertTrue(events.stream().noneMatch(AguiEvent.RunError.class::isInstance));
                var cards = events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                        .filter(event -> ToolResultPresentation.EVENT_NAME.equals(event.name())).toList();
                assertEquals(1, cards.size());
                Map<?, ?> value = (Map<?, ?>) cards.getFirst().value();
                assertEquals("直接图片答复", value.get("text"));
                var image = ((List<ToolResultPresentation.Image>) value.get("images")).getFirst();
                assertNull(image.error());
                assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(workspace.resolve(image.filePath())));
                assertEquals(1, model.calls);
                var followup = adapter.runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("分析刚才的截图").build()), "direct-session", "second")
                        .collectList().block(Duration.ofSeconds(15));
                assertNotNull(followup);
                assertTrue(followup.stream().noneMatch(AguiEvent.RunError.class::isInstance));
                assertEquals(2, model.calls);
                assertTrue(model.lastInput.stream().anyMatch(message -> message.getRole() == MsgRole.USER
                        && !message.getContentBlocks(ImageBlock.class).isEmpty()));
                assertTrue(model.lastInput.stream().filter(message -> message.getRole() == MsgRole.ASSISTANT)
                        .allMatch(message -> message.getContentBlocks(ImageBlock.class).isEmpty()));
            }
        }
    }

    @Test void testToolImagesReachNextReasoningAndStreamWhenToolDetailsAreDisabled() throws Exception {
        Path workspace = Files.createTempDirectory("image-stream-");
        try (var beans = Mockito.mockStatic(BeanUtils.class)) {
            beans.when(() -> BeanUtils.getBean(AttachService.class)).thenReturn(Mockito.mock(AttachService.class));
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            String base64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(new AgentTool() {
                public String getName() { return "screenshot"; }
                public String getDescription() { return "返回截图"; }
                public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
                public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                    return Mono.just(ToolResultBlock.of(List.of(TextBlock.builder().text("截图完成").build(),
                            new ImageBlock(new Base64Source("image/png", base64)))));
                }
            });
            RecordingModel model = new RecordingModel();
            var support = new ToolResultImageSupport() {
                public ToolResultBlock normalize(ToolResultBlock result, Path ignored) {
                    return super.normalize(result, workspace);
                }
            };
            ReActAgent agent = ReActAgent.builder().name("image-test").model(new ToolResultImageModel(model))
                    .toolkit(toolkit).maxIters(3).hooks(List.of(new ToolResultImageHook(support))).build();
            AgentMetadataStore.put(agent.getAgentId(), "tenantCode", "test");
            AgentMetadataStore.put(agent.getAgentId(), "threadId", "123");
            AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", false);
            var events = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig())
                    .runWithMessages(List.of(Msg.builder().role(MsgRole.USER).textContent("获取截图并分析").build()), "123", "run")
                    .collectList().block(Duration.ofSeconds(15));
            assertNotNull(events);
            assertTrue(events.stream().noneMatch(AguiEvent.RunError.class::isInstance),
                    () -> events.stream().filter(AguiEvent.RunError.class::isInstance).toList().toString());
            var custom = events.stream().filter(AguiEvent.Custom.class::isInstance).map(AguiEvent.Custom.class::cast)
                    .filter(event -> ToolResultPresentation.EVENT_NAME.equals(event.name())).findFirst().orElseThrow();
            Map<?, ?> value = (Map<?, ?>) custom.value();
            assertEquals("截图完成", value.get("text"));
            var image = ((List<ToolResultPresentation.Image>) value.get("images")).getFirst();
            assertTrue(Files.isRegularFile(workspace.resolve(image.filePath())));
            assertEquals(2, model.calls);
            assertTrue(model.lastInput.stream().anyMatch(message -> message.getRole() == MsgRole.USER
                    && !message.getContentBlocks(ImageBlock.class).isEmpty()));
            assertTrue(agent.getAgentState(null, "123").getContext().stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(result -> result.getOutput().stream()).anyMatch(ImageBlock.class::isInstance));
        } finally {
            try (var files = Files.walk(workspace)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static class RecordingModel implements Model {
        int calls;
        List<Msg> lastInput;
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            lastInput = messages;
            calls++;
            return Flux.just(ChatResponse.builder().content(calls == 1
                    ? List.of(ToolUseBlock.builder().id("image-call").name("screenshot").input(Map.of()).build())
                    : List.of(TextBlock.builder().text("图片已纳入回复").build())).build());
        }
        public String getModelName() { return "recording-vision-model"; }
    }
}
