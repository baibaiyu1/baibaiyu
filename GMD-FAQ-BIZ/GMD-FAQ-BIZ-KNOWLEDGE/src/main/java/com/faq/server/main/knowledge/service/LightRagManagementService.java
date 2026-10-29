package com.gmd.faq.server.main.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 平台本地管理页面的服务连接；仅开放文档操作和图谱读取，远端密钥不进入页面。 */
@Service
@RequiredArgsConstructor
public class LightRagManagementService {
    private static final Set<String> READ_PATHS = Set.of("/health", "/graphs", "/graph/label/list",
            "/graph/label/popular", "/graph/label/search", "/documents/supported_file_types",
            "/documents/pipeline_status");
    private static final Set<String> POST_PATHS = Set.of("/documents/paginated", "/documents/upload",
            "/documents/text", "/documents/texts", "/documents/scan", "/documents/reprocess_failed",
            "/documents/cancel_pipeline");
    private static final Set<String> GRAPH_QUERY_KEYS = Set.of("label", "max_depth", "max_nodes", "limit", "q");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final KnowledgeBaseConfigService configs;
    private final ResourceVisibilityService visibility;
    private final ObjectMapper objectMapper;
    private final LightRagModelSyncService modelSync;
    private final LightRagConnectionResolver connections;

    public com.fasterxml.jackson.databind.JsonNode syncModels(Long id) {
        return modelSync.sync(manageable(id));
    }

    public boolean prepare(Long id) {
        KnowledgeBaseConfig config = manageable(id);
        modelSync.ensureReady(config);
        client(config).get().uri(target(config, "/auth/verify", "")).retrieve().toBodilessEntity();
        client(config).get().uri(target(config, "/health", "")).retrieve().toBodilessEntity();
        return true;
    }

    /** 原生页面需要保留原始 HTTP 状态、媒体类型及 multipart，不能包装为平台 JSON。 */
    public void proxy(Long id, String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            KnowledgeBaseConfig config = manageable(id);
            assertAllowedPath(request.getMethod(), path);
            if (!"management".equals(request.getHeader("X-GMD-LightRAG"))
                    || Set.of("cross-site", "same-site").contains(String.valueOf(request.getHeader("Sec-Fetch-Site")))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请从知识库管理页面操作");
            }
            if (LightRagConnectionResolver.isBuiltin(config.getConnectionConfig())
                    || (!"GET".equals(request.getMethod()) && !Set.of("/documents/paginated", "/documents/cancel_pipeline").contains(path)))
                modelSync.ensureReady(config);
            RestClient.RequestBodySpec outgoing = client(config).method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target(config, path, query(path, request)));
            if (request instanceof MultipartHttpServletRequest multipart) {
                var body = new LinkedMultiValueMap<String, Object>();
                multipart.getMultiFileMap().forEach((name, files) -> files.forEach(file -> body.add(name, file.getResource())));
                multipart.getParameterMap().forEach((name, values) -> {
                    if (Set.of("accessToken", "token", "apiKey").contains(name)) return;
                    for (String value : values) body.add(name, value);
                });
                outgoing.contentType(MediaType.MULTIPART_FORM_DATA).body(body);
            } else if (!"GET".equals(request.getMethod())) {
                if (request.getContentType() != null) outgoing.header(HttpHeaders.CONTENT_TYPE, request.getContentType());
                outgoing.body(output -> request.getInputStream().transferTo(output));
            }
            outgoing.exchange((sent, upstream) -> {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                // 不透传 Set-Cookie、Location、CORS 或认证头，避免改变平台登录及泄漏凭证。
                response.setStatus(upstream.getStatusCode().value());
                MediaType type = upstream.getHeaders().getContentType();
                if (type != null) response.setContentType(type.toString());
                upstream.getBody().transferTo(response.getOutputStream());
                return null;
            });
        } catch (ResponseStatusException ex) {
            writeError(response, ex.getStatusCode().value(), ex.getReason());
        } catch (RestClientException ex) {
            writeError(response, 502, "无法连接知识图谱服务，请检查服务地址、API 密钥和服务状态");
        } catch (IllegalArgumentException ex) {
            writeError(response, 409, ex.getMessage());
        }
    }

    private KnowledgeBaseConfig manageable(Long id) {
        if (!UserUtils.hasPerm("knowledge:config:edit") || UserUtils.isSk() || UserUtils.isChatKey()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无知识库管理权限");
        }
        KnowledgeBaseConfig config;
        try {
            visibility.assertViewable("KNOWLEDGE", id);
            config = configs.getById(id);
            ResourceOwnershipSupport.assertManageable(config, "管理文档和图谱");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        }
        if (config.getKbType() != KbType.LIGHTRAG) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅知识图谱类型支持文档和图谱管理");
        }
        return config;
    }

    private RestClient client(KnowledgeBaseConfig config) {
        var factory = new JdkClientHttpRequestFactory(HTTP_CLIENT);
        int timeout = config.getHttpConfig() == null ? 180 : config.getHttpConfig().path("timeoutSeconds").asInt(180);
        factory.setReadTimeout(Duration.ofSeconds(Math.clamp(timeout, 1, 600)));
        var builder = RestClient.builder().requestFactory(factory);
        String key = connections.resolve(config).apiKey();
        if (!key.isEmpty()) builder.defaultHeader("X-API-Key", key);
        return builder.build();
    }

    private URI target(KnowledgeBaseConfig config, String path, String query) {
        try {
            String base = connections.resolve(config).baseUrl();
            URI uri = URI.create(base);
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return URI.create(base + path + query);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请配置有效的知识图谱 HTTP(S) 服务地址");
        }
    }

    private void assertAllowedPath(String method, String path) {
        if (path == null || path.contains("..") || path.contains("//") || path.contains("%") || path.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不支持的管理接口");
        }
        boolean allowed = ("GET".equals(method) && (READ_PATHS.contains(path)
                || path.matches("/documents/track_status/[A-Za-z0-9_-]+")))
                || ("POST".equals(method) && POST_PATHS.contains(path))
                || ("DELETE".equals(method) && Set.of("/documents", "/documents/delete_document").contains(path));
        if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "不支持的管理接口");
    }

    private String query(String path, HttpServletRequest request) {
        Set<String> keys;
        if (path.equals("/documents")) keys = Set.of("clear_llm_cache");
        else if (path.equals("/graphs") || path.startsWith("/graph/label/")) keys = GRAPH_QUERY_KEYS;
        else return "";
        var query = new StringBuilder();
        keys.forEach(key -> {
            String value = request.getParameter(key);
            if (value != null) query.append(query.isEmpty() ? '?' : '&').append(key).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return query.toString();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) return;
        response.reset();
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Map.of("detail", message == null ? "管理请求失败" : message));
    }
}
