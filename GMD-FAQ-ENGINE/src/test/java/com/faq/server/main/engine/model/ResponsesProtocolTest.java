package com.gmd.faq.server.main.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.enums.*;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.model.impl.DefaultOpenAIModelI;
import com.gmd.faq.server.main.engine.formatter.ToolResultImageModel;
import com.gmd.faq.server.main.engine.tool.PermissionAwareTool;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** 经真实 HTTP 验证供应商适配；不调用付费服务。 */
class ResponsesProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger effects = new AtomicInteger();
    private final List<String> headers = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ModelConfigWrapper config;
    private final DefaultOpenAIModelI factory = new DefaultOpenAIModelI(new ChatModelTransportConfig());
    private boolean functions;
    private boolean omitUsageDetails;
    private boolean omitUsage;
    private boolean reasoning;
    private boolean rawReasoning;
    private boolean reasoningDeltas = true;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            requests.add(request); paths.add(exchange.getRequestURI().getPath());
            headers.add(exchange.getRequestHeaders().getFirst("X-Project-Test"));
            int code = "fixture-backup".equals(request.path("model").asText()) ? 200 : status.get();
            boolean forced = "required".equals(request.path("tool_choice").asText()) || request.path("tool_choice").isObject();
            boolean thinkingOff = "none".equals(request.path("reasoning").path("effort").asText())
                    || "disabled".equals(request.path("thinking").path("type").asText());
            boolean incompatibleChoice = request.path("model").asText().startsWith("deepseek") && forced && !thinkingOff;
            if (incompatibleChoice) code = 400;
            String raw;
            if (code != 200) raw = json.writeValueAsString(Map.of("error", Map.of("message",
                    incompatibleChoice ? "Thinking mode does not support this tool_choice" : "fixture error", "type", "fixture")));
            else if (exchange.getRequestURI().getPath().endsWith("/chat/completions"))
                raw = "{\"id\":\"chat\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"原协议\"},\"finish_reason\":\"stop\"}]}";
            else if (request.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                raw = "";
                if (reasoningDeltas && (rawReasoning || reasoning)) {
                    String type = rawReasoning ? "response.reasoning_text.delta" : "response.reasoning_summary_text.delta";
                    raw += event(Map.of("type", type, "sequence_number", 0, "item_id", "rs_fixture", "output_index", 0,
                            "content_index", 0, "summary_index", 0, "delta", rawReasoning ? "原始" : "推理"));
                    raw += event(Map.of("type", type, "sequence_number", 1, "item_id", "rs_fixture", "output_index", 0,
                            "content_index", 0, "summary_index", 0, "delta", rawReasoning ? "思考" : "摘要"));
                }
                raw += event(Map.of("type", "response.output_text.delta", "sequence_number", 2, "item_id", "msg", "output_index", 1, "content_index", 0, "delta", "流式结果", "logprobs", List.of()))
                        + event(Map.of("type", "response.completed", "sequence_number", 2, "response", payload(text("流式结果"))));
            } else {
                boolean hasOutput = request.path("input").isArray() && java.util.stream.StreamSupport.stream(request.path("input").spliterator(), false)
                        .anyMatch(item -> "function_call_output".equals(item.path("type").asText()));
                Object item = functions && !hasOutput
                        ? Map.of("type", "function_call", "id", "fc_item", "call_id", "call_identity", "name", "lookup", "arguments", "{}", "status", "completed")
                        : text("最终回答");
                raw = json.writeValueAsString(payload(item));
            }
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            if (exchange.getResponseHeaders().getFirst("Content-Type") == null) exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        config = ModelConfigWrapper.builder().provider(ModelProviderType.OPEN_AI).apiType(ModelApiType.RESPONSES)
                .modelCode("fixture-model").apiKey("fixture-key").baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .streaming(false).thinking(false).maxTokens(1024).contextWindow(8192).toolChoiceStrategy(ToolChoiceStrategy.AUTO)
                .temperature(0.7).topP(0.9).topK(50).repeatPenalty(1.0).seed(42L).headers(Map.of("X-Project-Test", "configured"))
                .bodyParams(Map.of("reasoning.effort", "high")).build();
    }
    @AfterEach void cleanup() { if (server != null) server.stop(0); }

    @Test void responsesAndConnectivityUseTheSelectedProtocolAndCompatibleParameters() {
        var model = factory.getModel(config);
        var reply = model.stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertNotNull(reply); assertEquals("最终回答", reply.getContent().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast).findFirst().orElseThrow().getText());
        assertEquals(8192, model.getContextWindowSize());
        assertEquals("/v1/responses", paths.getFirst()); assertEquals("configured", headers.getFirst());
        var request = requests.getFirst();
        assertFalse(request.path("store").asBoolean(true)); assertFalse(request.has("previous_response_id"));
        assertEquals("reasoning.encrypted_content", request.path("include").get(0).asText());
        assertEquals("high", request.path("reasoning").path("effort").asText());
        assertEquals(1024, request.path("max_output_tokens").asInt());
        for (String field : List.of("top_k", "presence_penalty", "frequency_penalty", "seed", "temperature", "top_p")) assertFalse(request.has(field), field);
        config.setStreaming(true);
        factory.getSimpleModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("/v1/responses", paths.get(1)); assertFalse(requests.get(1).path("stream").asBoolean());
        assertEquals(256, requests.get(1).path("max_output_tokens").asInt());
        config.setApiType(null); config.setStreaming(false); config.setBodyParams(Map.of());
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("/v1/chat/completions", paths.get(2));
    }

    @Test void nativeFunctionCallIdsAndOutputsSurviveTheRealReactLoop() {
        functions = true;
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new PermissionAwareTool(new ToolBase(ToolBase.builder().name("lookup").description("lookup")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                assertEquals("call_identity", param.getToolUseBlock().getId());
                effects.incrementAndGet(); return Mono.just(ToolResultBlock.text("原始工具结果"));
            }
        }));
        try (var agent = ReActAgent.builder().name("responses").model(factory.getModel(config)).toolkit(toolkit).maxIters(3).build()) {
            Msg reply = agent.call(user()).block(Duration.ofSeconds(10));
            assertEquals("最终回答", reply.getTextContent());
        }
        assertEquals(1, effects.get()); assertEquals(2, requests.size());
        var input = requests.get(1).path("input");
        var outputs = java.util.stream.StreamSupport.stream(input.spliterator(), false)
                .filter(item -> "function_call_output".equals(item.path("type").asText())).toList();
        assertEquals(1, outputs.size()); assertEquals("call_identity", outputs.getFirst().path("call_id").asText());
        assertTrue(outputs.getFirst().path("output").asText().contains("原始工具结果"));
    }

    @Test void directAnswerImageHistoryIsAcceptedByTheNativeResponsesMapperOnTheNextTurn() {
        Msg direct = Msg.builder().role(MsgRole.ASSISTANT)
                .metadata(Map.of(MessageMetadataKeys.TOOL_RETURN_DIRECT, true))
                .content(TextBlock.builder().text("直接返回的图片").build(),
                        ImageBlock.builder().source(new Base64Source("image/png", "aW1hZ2U=")).build()).build();
        new ToolResultImageModel(factory.getModel(config))
                .stream(List.of(user(), direct, user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        var inputs = java.util.stream.StreamSupport.stream(requests.getFirst().path("input").spliterator(), false).toList();
        var visual = inputs.stream().filter(item -> item.path("content").toString().contains("input_image")).findFirst().orElseThrow();
        assertEquals("user", visual.path("role").asText());
        assertTrue(visual.toString().contains("data:image/png;base64,aW1hZ2U="));
        assertTrue(inputs.stream().anyMatch(item -> "assistant".equals(item.path("role").asText())
                && item.path("content").toString().contains("直接返回的图片")));
        assertEquals(1, direct.getContentBlocks(ImageBlock.class).size());
    }

    @Test void streamingTypedEventsAndUsageAreMappedWithoutRepeatingText() {
        config.setStreaming(true);
        var chunks = factory.getModel(config).stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(10));
        assertNotNull(chunks);
        String text = chunks.stream().flatMap(chunk -> chunk.getContent().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
        assertEquals("流式结果", text);
        ChatUsage usage = chunks.stream().map(ChatResponse::getUsage).filter(Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(10, usage.getInputTokens()); assertEquals(4, usage.getOutputTokens());
        assertTrue(requests.getFirst().path("stream").asBoolean());
    }

    @Test void requestErrorsDoNotAddHiddenProviderRetriesOrLeakConfiguration() {
        status.set(429);
        assertThrows(RuntimeException.class, () -> factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10)));
        assertEquals(1, requests.size());
        assertFalse(config.toString().contains("fixture-key")); assertFalse(config.toString().contains("configured"));
        config.setBodyParams(Map.of("store", true)); assertThrows(IllegalArgumentException.class, () -> factory.getModel(config));
        config.setBodyParams(Map.of("previous_response_id", "another-session")); assertThrows(IllegalArgumentException.class, () -> factory.getModel(config));
        config.setBodyParams(Map.of()); config.setQueryParams(Map.of("secret", "value"));
        assertThrows(IllegalArgumentException.class, () -> factory.getModel(config));
    }

    @Test void realStreamingResponses429UsesTheOuterBudgetAndThenTheNativeBackupProtocol() {
        status.set(429); config.setStreaming(true);
        var primary = factory.getModel(config);
        config.setModelCode("fixture-backup");
        var backup = factory.getModel(config);
        var observed = org.mockito.Mockito.mock(com.gmd.faq.server.main.engine.telemetry.AgentTelemetry.class);
        var chunks = new ResilientModel(primary, backup, 2, observed, 1)
                .stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(10));
        assertNotNull(chunks);
        assertEquals("流式结果", chunks.stream().flatMap(chunk -> chunk.getContent().stream())
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat));
        assertEquals(List.of("fixture-model", "fixture-model", "fixture-backup"),
                requests.stream().map(request -> request.path("model").asText()).toList());
        assertTrue(paths.stream().allMatch(path -> path.endsWith("/responses")));
    }

    @Test void optionalUsageDetailsAndUnknownUsageDoNotDiscardAValidAnswer() {
        var model = factory.getModel(config);
        omitUsageDetails = true;
        var reply = model.stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertNotNull(reply); assertEquals(10, reply.getUsage().getInputTokens());
        omitUsage = true;
        reply = model.stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertNotNull(reply); assertNull(reply.getUsage());
        assertFalse(reply.getContent().isEmpty());
    }

    @Test void statelessReasoningReplaysEncryptedContentAndAcceptsLegacySummaryHistory() {
        reasoning = true;
        var model = factory.getModel(config);
        var reply = model.stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        var thinking = (ThinkingBlock) reply.getContent().stream().filter(ThinkingBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals("encrypted-fixture", thinking.getMetadata().get("openai.reasoning.encrypted_content"));
        var assistant = Msg.builder().role(MsgRole.ASSISTANT).content(reply.getContent()).build();
        model.stream(List.of(user(), assistant, user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        var input = requests.get(1).path("input");
        assertTrue(java.util.stream.StreamSupport.stream(input.spliterator(), false).anyMatch(item ->
                "reasoning".equals(item.path("type").asText()) && "encrypted-fixture".equals(item.path("encrypted_content").asText())));
        var legacy = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                ThinkingBlock.builder().thinking("旧协议的推理摘要").build(), TextBlock.builder().text("旧回答").build())).build();
        model.stream(List.of(user(), legacy, user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertTrue(requests.get(2).path("input").toString().contains("旧回答"));
        assertFalse(requests.get(2).path("input").toString().contains("旧协议的推理摘要"));
        assertEquals(2, legacy.getContent().size());
    }

    @Test void deepSeekMaxIsSentUnchangedOnBothProtocolsAndDisabledCannotRetainOldEffort() throws Exception {
        config.setModelCode("deepseek-flash");
        config.setReasoning(json.readTree("{\"mode\":\"ENABLED\",\"effort\":\"max\",\"showThinking\":true}"));
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("max", requests.getFirst().path("reasoning").path("effort").asText());
        assertFalse(requests.getFirst().has("include"));
        assertFalse(requests.getFirst().path("reasoning").has("summary"));
        config.setApiType(ModelApiType.CHAT_COMPLETIONS);
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("max", requests.get(1).path("reasoning_effort").asText());
        assertEquals("enabled", requests.get(1).path("thinking").path("type").asText());
        config.setApiType(ModelApiType.RESPONSES);
        config.setReasoning(json.readTree("{\"mode\":\"DISABLED\",\"showThinking\":true}"));
        factory.getSimpleModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("none", requests.get(2).path("reasoning").path("effort").asText());
        assertFalse(requests.get(2).path("reasoning").has("summary"));
        assertEquals(1024, requests.get(2).path("max_output_tokens").asInt());
        config.setApiType(ModelApiType.CHAT_COMPLETIONS);
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("disabled", requests.get(3).path("thinking").path("type").asText());
        assertEquals("none", requests.get(3).path("reasoning_effort").asText());
    }

    @Test void deepSeekThinkingUsesCompatibleChoicesForConfiguredAndPerCallStrategiesOnBothProtocols() throws Exception {
        config.setModelCode("deepseek-flash"); config.setBodyParams(Map.of());
        var lookup = ToolSchema.builder().name("lookup").description("lookup")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
        var other = ToolSchema.builder().name("other").description("other")
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
        for (var protocol : List.of(ModelApiType.CHAT_COMPLETIONS, ModelApiType.RESPONSES)) {
            config.setApiType(protocol);
            for (String mode : List.of("ENABLED", "DEFAULT")) {
                config.setReasoning(json.readTree("{\"mode\":\"" + mode + "\"}"));
                config.setToolChoiceStrategy(ToolChoiceStrategy.REQUIRED);
                Model model = factory.getModel(config);
                model.stream(List.of(user()), List.of(lookup, other), null).blockLast(Duration.ofSeconds(10));
                assertEquals("auto", requests.getLast().path("tool_choice").asText());
                assertEquals(2, requests.getLast().path("tools").size());
                assertEquals("deepseek-flash", model.getModelName());
                if (protocol == ModelApiType.RESPONSES) assertEquals(8192, model.getContextWindowSize());
                assertEquals(ToolChoiceStrategy.REQUIRED, config.getToolChoiceStrategy());
                if ("ENABLED".equals(mode)) assertEquals("high", protocol == ModelApiType.RESPONSES
                        ? requests.getLast().path("reasoning").path("effort").asText()
                        : requests.getLast().path("reasoning_effort").asText(), "兼容策略不能关闭已配置的推理");

                config.setToolChoiceStrategy(ToolChoiceStrategy.SPECIFIC); config.setSpecificToolName("lookup");
                factory.getModel(config).stream(List.of(user()), List.of(lookup, other), null).blockLast(Duration.ofSeconds(10));
                var selected = requests.getLast();
                assertEquals("auto", selected.path("tool_choice").asText());
                assertEquals(1, selected.path("tools").size());
                assertEquals("lookup", (protocol == ModelApiType.RESPONSES ? selected.path("tools").get(0)
                        : selected.path("tools").get(0).path("function")).path("name").asText());

                config.setToolChoiceStrategy(ToolChoiceStrategy.AUTO);
                factory.getModel(config).stream(List.of(user()), List.of(lookup, other),
                        GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build()).blockLast(Duration.ofSeconds(10));
                assertEquals("auto", requests.getLast().path("tool_choice").asText(), "运行时覆盖也必须经过兼容处理");
                factory.getModel(config).stream(List.of(user()), List.of(lookup, other),
                        GenerateOptions.builder().toolChoice(new ToolChoice.None()).build()).blockLast(Duration.ofSeconds(10));
                assertEquals("none", requests.getLast().path("tool_choice").asText(), "禁用工具不能被兼容处理恢复");
            }
            config.setReasoning(json.readTree("{\"mode\":\"DISABLED\"}"));
            config.setToolChoiceStrategy(ToolChoiceStrategy.REQUIRED);
            factory.getModel(config).stream(List.of(user()), List.of(lookup, other), null).blockLast(Duration.ofSeconds(10));
            assertEquals("required", requests.getLast().path("tool_choice").asText(), "明确关闭推理后保留强制策略");

            config.setReasoning(null); config.setBodyParams(Map.of()); config.setModelCode("deepseek-reasoner");
            factory.getModel(config).stream(List.of(user()), List.of(lookup), null).blockLast(Duration.ofSeconds(10));
            assertEquals("auto", requests.getLast().path("tool_choice").asText(), "旧固定推理模型同样适配");
            config.setModelCode("deepseek-flash");
            config.setToolChoiceStrategy(ToolChoiceStrategy.SPECIFIC); config.setSpecificToolName("unavailable");
            int before = requests.size();
            assertThrows(IllegalArgumentException.class, () -> factory.getModel(config)
                    .stream(List.of(user()), List.of(lookup), null).blockLast(Duration.ofSeconds(10)));
            assertEquals(before, requests.size(), "权限或方案限制排除的工具不能重新提供给模型");
        }
        config.setModelCode("gpt-5.2"); config.setReasoningProfile("AUTO");
        config.setReasoning(json.readTree("{\"mode\":\"ENABLED\"}"));
        config.setToolChoiceStrategy(ToolChoiceStrategy.REQUIRED);
        factory.getModel(config).stream(List.of(user()), List.of(lookup), null).blockLast(Duration.ofSeconds(10));
        assertEquals("required", requests.getLast().path("tool_choice").asText(), "其他模型继续使用原策略");
    }

    @Test void deepSeekRequiredStrategyCompletesTheRealToolLoopWithoutDisablingThinking() throws Exception {
        config.setModelCode("deepseek-flash"); config.setBodyParams(Map.of()); functions = true;
        config.setReasoning(json.readTree("{\"mode\":\"ENABLED\",\"effort\":\"high\"}"));
        config.setToolChoiceStrategy(ToolChoiceStrategy.REQUIRED);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name("lookup").description("lookup")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                effects.incrementAndGet(); return Mono.just(ToolResultBlock.text("查询结果"));
            }
        });
        try (var agent = ReActAgent.builder().name("deepseek").model(factory.getModel(config)).toolkit(toolkit).maxIters(3).build()) {
            assertEquals("最终回答", agent.call(user()).block(Duration.ofSeconds(10)).getTextContent());
        }
        assertEquals(1, effects.get()); assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(request -> "auto".equals(request.path("tool_choice").asText())
                && "high".equals(request.path("reasoning").path("effort").asText())));
    }

    @Test void openAiRequestsSummaryWithoutForcingEffortWhenUsingProviderDefault() throws Exception {
        config.setModelCode("gpt-5.2");
        config.setReasoning(json.readTree("{\"mode\":\"DEFAULT\",\"showThinking\":true}"));
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertEquals("auto", requests.getFirst().path("reasoning").path("summary").asText());
        assertFalse(requests.getFirst().path("reasoning").has("effort"));
        config.setReasoning(json.readTree("{\"mode\":\"DEFAULT\",\"showThinking\":false}"));
        factory.getModel(config).stream(List.of(user()), List.of(), null).blockLast(Duration.ofSeconds(10));
        assertFalse(requests.get(1).path("reasoning").has("summary"));
    }

    @Test void rawReasoningWithoutSummaryIsVisibleAndReplayedThroughTheActualToolLoop() {
        rawReasoning = true; functions = true; config.setMulti(true);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new ToolBase(ToolBase.builder().name("lookup").description("lookup")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return Mono.just(ToolResultBlock.text("result")); }
        });
        try (var agent = ReActAgent.builder().name("raw-reasoning").model(factory.getModel(config)).toolkit(toolkit).maxIters(3).build()) {
            var reply = agent.call(user()).block(Duration.ofSeconds(10));
            assertNotNull(reply);
            assertEquals("原始思考", reply.getFirstContentBlock(ThinkingBlock.class).getThinking());
        }
        assertEquals(2, requests.size());
        var reasoningInput = java.util.stream.StreamSupport.stream(requests.get(1).path("input").spliterator(), false)
                .filter(item -> "reasoning".equals(item.path("type").asText())).findFirst().orElseThrow();
        assertEquals("原始思考", reasoningInput.path("content").get(0).path("text").asText());
        assertFalse(reasoningInput.has("encrypted_content"));
    }

    @Test void rawAndSummaryStreamsDisplayExactlyOnceIncludingTerminalOnlyReasoning() {
        config.setStreaming(true);
        for (boolean raw : List.of(false, true)) for (boolean deltas : List.of(false, true)) {
            rawReasoning = raw; reasoning = !raw; reasoningDeltas = deltas;
            var chunks = factory.getModel(config).stream(List.of(user()), List.of(), null).collectList().block(Duration.ofSeconds(10));
            assertNotNull(chunks);
            String visible = chunks.stream().flatMap(chunk -> chunk.getContent().stream()).filter(ThinkingBlock.class::isInstance)
                    .map(ThinkingBlock.class::cast).map(ThinkingBlock::getThinking).filter(Objects::nonNull).reduce("", String::concat);
            assertEquals(raw ? "原始思考" : "推理摘要", visible, "raw=" + raw + ", deltas=" + deltas);
            assertEquals("流式结果", chunks.stream().flatMap(chunk -> chunk.getContent().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat));
            if (raw) assertTrue(chunks.stream().flatMap(chunk -> chunk.getContent().stream()).filter(ThinkingBlock.class::isInstance)
                    .map(ThinkingBlock.class::cast).anyMatch(block -> block.getMetadata() != null
                            && "原始思考".equals(block.getMetadata().get("openai.reasoning.text"))));
        }
    }

    private JsonNode payload(Object output) {
        com.fasterxml.jackson.databind.node.ObjectNode value = json.valueToTree(response(output));
        if (reasoning) ((com.fasterxml.jackson.databind.node.ArrayNode) value.get("output")).insert(0, json.valueToTree(
                Map.of("id", "rs_fixture", "type", "reasoning", "summary", List.of(Map.of("type", "summary_text", "text", "推理摘要")),
                        "encrypted_content", "encrypted-fixture")));
        if (rawReasoning) ((com.fasterxml.jackson.databind.node.ArrayNode) value.get("output")).insert(0, json.valueToTree(
                Map.of("id", "rs_fixture", "type", "reasoning", "content", List.of(Map.of("type", "reasoning_text", "text", "原始思考")))));
        if (omitUsage) value.remove("usage");
        else if (omitUsageDetails) ((com.fasterxml.jackson.databind.node.ObjectNode) value.get("usage"))
                .remove(List.of("input_tokens_details", "output_tokens_details"));
        return value;
    }

    private static Msg user() { return Msg.builder().role(MsgRole.USER).textContent("测试").build(); }
    private static Object text(String value) { return Map.of("type", "message", "id", "msg", "role", "assistant", "status", "completed",
            "content", List.of(Map.of("type", "output_text", "text", value, "annotations", List.of(), "logprobs", List.of()))); }
    private static Map<String, Object> response(Object output) { return Map.of("id", "resp_fixture", "object", "response", "created_at", 1,
            "status", "completed", "model", "fixture-model", "output", List.of(output), "usage", Map.of("input_tokens", 10, "output_tokens", 4, "total_tokens", 14,
                    "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens_details", Map.of("reasoning_tokens", 0))); }
    private String event(Object value) throws java.io.IOException { return "data: " + json.writeValueAsString(value) + "\n\n"; }
}
