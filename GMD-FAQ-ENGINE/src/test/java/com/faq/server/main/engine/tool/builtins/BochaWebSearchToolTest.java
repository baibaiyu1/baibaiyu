package com.gmd.faq.server.main.engine.tool.builtins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.tool.web.BochaSearchProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BochaWebSearchToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BochaSearchProperties properties = new BochaSearchProperties();

    @Test void mapsChineseDateAndSiteFiltersAndPreservesOnlyBoundedCitableWebResults() throws Exception {
        properties.setApiKey("test-key");
        AtomicReference<Request> request = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        var tool = tool(chain -> {
            request.set(chain.request());
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            payload.set(mapper.readTree(buffer.readUtf8()));
            return response(chain, 200, """
                    {"code":200,"data":{"webPages":{"value":[
                      {"name":"暴雨防范通报","url":"https://www.mem.gov.cn/news/1#top","siteName":"应急管理部",
                       "datePublished":"2026-10-26T09:00:00+08:00","summary":"已启动防汛应急响应","snippet":"官方通报"},
                      {"name":"重复网页","url":"https://www.mem.gov.cn/news/1"},
                      {"name":"错误站点","url":"https://mem.gov.cn.example.com/news"},
                      {"name":"不安全协议","url":"javascript:alert(1)"},
                      {"name":"预警资料","url":"https://www.cma.gov.cn/news/2","summary":"气象资料"}
                    ]},"images":{"value":[{"contentUrl":"https://example.com/photo.png"}]}}}
                    """);
        });
        ToolResultBlock result = tool.search("  某地暴雨防范  ", "2026-10-01..2026-10-27", "mem.gov.cn|CMA.GOV.CN", 5);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("https://api.bocha.cn/v1/web-search", request.get().url().toString());
        assertEquals("Bearer test-key", request.get().header("Authorization"));
        assertEquals("某地暴雨防范", payload.get().path("query").asText());
        assertEquals("2026-10-01..2026-10-27", payload.get().path("freshness").asText());
        assertEquals("mem.gov.cn|cma.gov.cn", payload.get().path("include").asText());
        assertTrue(payload.get().path("summary").asBoolean());
        JsonNode output = mapper.readTree(text(result));
        assertEquals(2, output.path("results").size());
        assertEquals("https://www.mem.gov.cn/news/1", output.path("results").get(0).path("url").asText());
        assertEquals("2026-10-26T09:00:00+08:00", output.path("results").get(0).path("publishedAt").asText());
        assertTrue(output.path("results").get(1).path("publishedAt").isNull());
        assertFalse(text(result).contains("test-key"));
        assertFalse(text(result).contains("photo.png"));
    }

    @Test void missingKeyAndInvalidParametersMakeNoNetworkCalls() {
        AtomicInteger calls = new AtomicInteger();
        var tool = tool(chain -> { calls.incrementAndGet(); return response(chain, 200, "{}"); });
        assertTrue(text(tool.search("防灾资料", null, null, null)).contains("BOCHA_API_KEY"));
        properties.setApiKey("test-key");
        assertEquals(ToolResultState.ERROR, tool.search(" ", null, null, null).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", "2026-02-30", null, null).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", "2026-10-27..2026-10-01", null, null).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", "today", null, null).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", null, "https://www.mem.gov.cn/", null).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", null, null, 11).getState());
        assertEquals(ToolResultState.ERROR, tool.search("洪涝", null, null, 0).getState());
        assertEquals(0, calls.get());
    }

    @Test void defaultsRespectDeploymentLimitAndNoResultsRemainSuccessful() throws Exception {
        properties.setApiKey("test-key"); properties.setMaxResults(2);
        var tool = tool(chain -> {
            Buffer buffer = new Buffer(); chain.request().body().writeTo(buffer);
            JsonNode payload = mapper.readTree(buffer.readUtf8());
            assertEquals(2, payload.path("count").asInt());
            assertEquals("noLimit", payload.path("freshness").asText());
            assertFalse(payload.has("include"));
            return response(chain, 200, "{\"code\":200,\"data\":{\"webPages\":{\"value\":[]}}}");
        });
        var result = tool.search("未命中的灾害事件", null, null, null);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(mapper.readTree(text(result)).path("results").isEmpty());
        assertTrue(text(result).contains("不代表没有事件"));
    }

    @ParameterizedTest @ValueSource(ints = {401, 402, 403, 429, 500, 503})
    void httpAndBusinessErrorsAreFailuresWithoutLeakingProviderBodies(int status) {
        properties.setApiKey("test-key");
        for (boolean envelope : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger();
            var tool = tool(chain -> {
                calls.incrementAndGet();
                return response(chain, envelope ? 200 : status,
                        "{\"code\":" + status + ",\"message\":\"secret-test-key internal-provider-detail\"}");
            });
            var result = tool.search("应急资料", null, null, null);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertFalse(text(result).contains("test-key"));
            assertFalse(text(result).contains("internal-provider-detail"));
            assertEquals(1, calls.get());
        }
    }

    @Test void actualQuotaResponseWith403IsReportedAsInsufficientQuota() {
        properties.setApiKey("test-key");
        for (int httpStatus : new int[]{403, 200}) {
            var tool = tool(chain -> response(chain, httpStatus,
                    "{\"code\":\"403\",\"message\":\"You do not have enough money or package quota\","
                            + "\"detail\":\"secret-test-key internal-provider-detail\"}"));
            var result = tool.search("应急避险资料", null, null, 1);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertTrue(text(result).contains("额度不足"));
            assertFalse(text(result).contains("认证失败"));
            assertFalse(text(result).contains("test-key"));
            assertFalse(text(result).contains("internal-provider-detail"));
        }
    }

    @Test void malformedOrOversized403BodyUsesFixedPermissionError() {
        properties.setApiKey("test-key");
        for (String body : new String[]{"not-json test-key", "null", "x".repeat(2 * 1024 * 1024 + 1)}) {
            var result = tool(chain -> response(chain, 403, body)).search("应急资料", null, null, 1);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertTrue(text(result).contains("认证失败"));
            assertFalse(text(result).contains("test-key"));
        }
    }

    @Test void malformedOversizedAndDisconnectedResponsesAreNotEmptySearchSuccesses() {
        properties.setApiKey("test-key");
        for (String body : new String[]{"not-json", "null", "{}", "{\"code\":200,\"data\":{\"webPages\":{\"value\":{}}}}", "x".repeat(2 * 1024 * 1024 + 1)}) {
            var tool = tool(chain -> response(chain, 200, body));
            assertEquals(ToolResultState.ERROR, tool.search("防灾", null, null, null).getState());
        }
        AtomicInteger calls = new AtomicInteger();
        var tool = tool(chain -> { calls.incrementAndGet(); throw new IOException("secret-test-key request failure"); });
        var result = tool.search("应急", null, null, null);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertFalse(text(result).contains("test-key"));
        assertEquals(1, calls.get());
    }

    @Test void credentialsCannotBeSentToAnArbitraryConfiguredHost() {
        assertThrows(IllegalArgumentException.class, () -> properties.setApiUrl("https://example.com/v1/web-search"));
        assertThrows(IllegalArgumentException.class, () -> properties.setApiUrl("http://api.bocha.cn/v1/web-search"));
        assertThrows(IllegalArgumentException.class, () -> properties.setApiUrl("https://api.bocha.cn/v1/web-search?key=test"));
        assertThrows(IllegalArgumentException.class, () -> properties.setApiKey("key\r\nAuthorization: other"));
        properties.setApiUrl("https://api.bochaai.com/v1/web-search");
        assertEquals("https://api.bochaai.com/v1/web-search", properties.getApiUrl());
    }

    @Test void actualHttpTransportCannotRepeatSearchOnRetryAfterZeroOrRedirect() throws Exception {
        properties.setApiKey("test-key");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(503);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.getResponseHeaders().set("Location", "/search");
            exchange.sendResponseHeaders(status.get(), -1); exchange.close();
        });
        server.start();
        try {
            var client = BochaWebSearchTool.createClient(properties).newBuilder().addInterceptor(chain ->
                    chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/search").build())).build();
            var tool = new BochaWebSearchTool(properties, mapper, client);
            assertEquals(ToolResultState.ERROR, tool.search("防灾", null, null, null).getState());
            assertEquals(1, calls.get());
            status.set(307); calls.set(0);
            assertEquals(ToolResultState.ERROR, tool.search("应急", null, null, null).getState());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private BochaWebSearchTool tool(Interceptor interceptor) {
        return new BochaWebSearchTool(properties, mapper, new OkHttpClient.Builder().addInterceptor(interceptor).build());
    }

    private static Response response(Interceptor.Chain chain, int status, String text) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(ResponseBody.create(text, MediaType.get("application/json; charset=utf-8"))).build();
    }

    static String text(ToolResultBlock result) {
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).collect(java.util.stream.Collectors.joining("\n"));
    }
}
