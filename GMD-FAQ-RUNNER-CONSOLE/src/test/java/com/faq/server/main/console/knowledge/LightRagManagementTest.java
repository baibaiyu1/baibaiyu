package com.gmd.faq.server.main.console.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.knowledge.service.LightRagManagementService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正经过 MVC 路由和本地 HTTP 上游，覆盖原生协议、上传及逐请求判权。 */
class LightRagManagementTest {
    private static final String ROOT = "/knowledge/config/42/lightrag";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KnowledgeBaseConfigService configs = mock(KnowledgeBaseConfigService.class);
    private final ResourceVisibilityService visibility = mock(ResourceVisibilityService.class);
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private KnowledgeBaseConfig config;
    private MockMvc mvc;
    private int apiStatus;

    record Received(String path, String query, String method, String key, String authorization, String cookie, String contentType, byte[] body) {}

    @BeforeEach
    void setup() throws Exception {
        apiStatus = 200;
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/prefix/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            var headers = exchange.getRequestHeaders();
            received.add(new Received(path, exchange.getRequestURI().getRawQuery(), exchange.getRequestMethod(),
                    headers.getFirst("X-API-Key"), headers.getFirst("Authorization"), headers.getFirst("Cookie"),
                    headers.getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            String body = "{\"status\":\"success\",\"track_id\":\"track-1\",\"documents\":[],\"nodes\":[],\"edges\":[]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Set-Cookie", "upstream-secret=hidden");
            exchange.getResponseHeaders().set("Location", "http://example.invalid/credentials");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(apiStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        config = new KnowledgeBaseConfig();
        config.setId(42L);
        config.setKbType(KbType.LIGHTRAG);
        config.setOwnerId(7L);
        config.setVisibility("PRIVATE");
        config.setConnectionConfig(JSON.createObjectNode().put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/prefix/")
                .put("apiKey", "remote-secret"));
        when(configs.getById(42L)).thenReturn(config);
        UserUtils.setBackgroundUserDetail(UserDetail.builder().id(7L).tenantId(9L).authType(UserUtils.AUTH_ACCOUNT)
                .perms(List.of("knowledge:config:edit")).build());
        var service = new LightRagManagementService(configs, visibility, JSON,
                mock(com.gmd.faq.server.main.knowledge.service.LightRagModelSyncService.class));
        mvc = MockMvcBuilders.standaloneSetup(new LightRagManagementController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON)).build();
    }

    @AfterEach
    void cleanup() {
        UserUtils.setBackgroundUserDetail(null);
        upstream.stop(0);
    }

    @Test
    void preparationVerifiesCredentialsAndHealthWithoutLoadingAnyFrontend() throws Exception {
        mvc.perform(get(ROOT + "/management")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertEquals(List.of("/prefix/auth/verify", "/prefix/health"), received.stream().map(Received::path).toList());
        assertTrue(received.stream().allMatch(item -> "remote-secret".equals(item.key()) && item.authorization() == null && item.cookie() == null));
    }

    @Test
    void remoteHtmlAndAssetsCannotBeLoadedThroughTheApiConnection() throws Exception {
        for (String path : List.of("/webui/platform.html", "/webui/index.html", "/webui/assets/native.js")) {
            mvc.perform(get(ROOT + path).header("X-GMD-LightRAG", "management")).andExpect(status().isNotFound());
        }
        assertTrue(received.isEmpty());
    }

    @Test
    void paginatedDocumentsKeepNativeJsonAndBody() throws Exception {
        String body = "{\"page\":1,\"page_size\":20,\"status_filter\":\"failed\"}";
        mvc.perform(post(ROOT + "/documents/paginated").header("X-GMD-LightRAG", "management")
                        .header("Authorization", "Bearer platform-token").header("Cookie", "gmd-faq-access-token=platform-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.track_id").value("track-1")).andExpect(jsonPath("$.code").doesNotExist());
        Received request = received.getFirst();
        assertEquals(body, new String(request.body(), StandardCharsets.UTF_8));
        assertNull(request.authorization());
        assertNull(request.cookie());
        assertEquals("remote-secret", request.key());
    }

    @Test
    void multipartUploadRetainsFileBytesFilenameAndFormFields() throws Exception {
        byte[] bytes = {0, 1, 2, 3, (byte) 255};
        mvc.perform(multipart(ROOT + "/documents/upload").file(new MockMultipartFile("file", "manual.pdf", "application/pdf", bytes))
                        .param("parser", "mineru").param("accessToken", "platform-secret").header("X-GMD-LightRAG", "management"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.track_id").value("track-1"));
        Received request = received.getFirst();
        assertTrue(request.contentType().startsWith("multipart/form-data;boundary="));
        String wire = new String(request.body(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("filename=\"manual.pdf\""));
        assertTrue(wire.contains("name=\"parser\""));
        assertTrue(wire.contains("mineru"));
        assertFalse(wire.contains("platform-secret"));
        assertTrue(wire.contains(new String(bytes, StandardCharsets.ISO_8859_1)));
    }

    @Test
    void graphQueryPreservesUnicodeButNeverForwardsPlatformTokens() throws Exception {
        mvc.perform(get(ROOT + "/graphs").param("label", "光明顶 & 设备").param("max_depth", "3")
                        .param("accessToken", "platform-secret").header("X-GMD-LightRAG", "management"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes").isArray());
        String query = received.getFirst().query();
        assertTrue(query.contains("max_depth=3"));
        assertTrue(query.contains("%E5%85%89%E6%98%8E%E9%A1%B6"));
        assertFalse(query.contains("platform-secret"));
    }

    @Test
    void clearingDocumentsKeepsExplicitCacheOptionWithoutForwardingOtherParameters() throws Exception {
        for (String clearCache : List.of("false", "true")) {
            mvc.perform(delete(ROOT + "/documents").param("clear_llm_cache", clearCache)
                            .param("accessToken", "platform-secret").param("delete_parsed_files", "true")
                            .header("X-GMD-LightRAG", "management"))
                    .andExpect(status().isOk());
        }
        assertEquals(List.of("clear_llm_cache=false", "clear_llm_cache=true"),
                received.stream().map(Received::query).toList());
        assertTrue(received.stream().allMatch(item -> "DELETE".equals(item.method())));
    }

    @Test
    void mutationsAreNotRetriedAndUpstreamStatusIsPreserved() throws Exception {
        apiStatus = 429;
        mvc.perform(post(ROOT + "/documents/scan").header("X-GMD-LightRAG", "management"))
                .andExpect(status().isTooManyRequests()).andExpect(header().doesNotExist("Location"));
        assertEquals(1, received.size());
    }

    @Test
    void sharingPreservesOwnerManagementButTransferredOwnershipIsRechecked() throws Exception {
        mvc.perform(get(ROOT + "/health").header("X-GMD-LightRAG", "management")).andExpect(status().isOk());
        config.setVisibility("TENANT");
        mvc.perform(post(ROOT + "/documents/scan").header("X-GMD-LightRAG", "management")).andExpect(status().isOk());
        config.setVisibility("PRIVATE");
        config.setOwnerId(8L);
        mvc.perform(get(ROOT + "/webui/platform.html")).andExpect(status().isForbidden());
        assertEquals(2, received.size());
        verify(visibility, times(3)).assertViewable("KNOWLEDGE", 42L);
    }

    @Test
    void administratorsCanManagePublicKnowledge() throws Exception {
        config.setVisibility("TENANT");
        config.setOwnerId(null);
        UserUtils.getBackgroundUserDetail().setPerms(List.of("knowledge:config:edit", "system:admin"));
        mvc.perform(get(ROOT + "/health").header("X-GMD-LightRAG", "management")).andExpect(status().isOk());
    }

    @Test
    void invisibleResourcesAndMissingPermissionsNeverReachUpstream() throws Exception {
        doThrow(new IllegalStateException("无权查看资源")).when(visibility).assertViewable("KNOWLEDGE", 42L);
        mvc.perform(get(ROOT + "/health").header("X-GMD-LightRAG", "management")).andExpect(status().isForbidden());
        UserUtils.getBackgroundUserDetail().setPerms(List.of("knowledge:config:list"));
        mvc.perform(get(ROOT + "/webui/platform.html")).andExpect(status().isForbidden());
        assertTrue(received.isEmpty());
    }

    @Test
    void queryGraphWritesSettingsAndPathTraversalAreNotProxied() throws Exception {
        for (String path : List.of("/query", "/graph/entity/edit", "/login", "/settings", "/webui/index.html", "/webui/assets/../secret.js")) {
            mvc.perform(post(ROOT + path).header("X-GMD-LightRAG", "management")).andExpect(status().isNotFound());
        }
        assertTrue(received.isEmpty());
    }

    @Test
    void cookieBasedMutationsRequireSameOriginManagementHeader() throws Exception {
        mvc.perform(post(ROOT + "/documents/scan")).andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/documents/scan").header("X-GMD-LightRAG", "management").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        assertTrue(received.isEmpty());
    }

    @Test
    void invalidCredentialsAndWrongKnowledgeTypeFailBeforeManagement() throws Exception {
        apiStatus = 403;
        mvc.perform(get(ROOT + "/management")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        config.setKbType(KbType.LOCAL);
        mvc.perform(get(ROOT + "/webui/platform.html")).andExpect(status().isBadRequest());
        assertEquals(1, received.size());
    }
}
