package com.gmd.faq.server.main.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 发布端点（MCP Server）
 * <p>
 * 把平台的工具以 MCP 协议对外发布，第三方 MCP 客户端（Claude Desktop、IDE 插件等）可通过该端点调用。
 * <ul>
 *   <li>路径：{@code POST /runtime/mcp}（经网关对外为 {@code /api/runtime/mcp}）</li>
 *   <li>鉴权：复用平台 API Key（{@code Authorization: sk-xxx}），租户由 SK 归属决定</li>
 *   <li>响应：默认 JSON；请求头带 {@code Accept: text/event-stream} 时按 SSE 返回单条 message 事件</li>
 *   <li>GET 用于服务端主动推送流：本实现无服务端推送，按协议返回 405；DELETE 用于会话关闭，返回 204</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/runtime/mcp")
@RequiredArgsConstructor
public class McpServerEndPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpProtocolHandler mcpProtocolHandler;

    /**
     * 处理 JSON-RPC 请求（支持单条与批量）
     */
    @SkAccess
    @PostMapping
    public ResponseEntity<?> handle(@RequestBody(required = false) JsonNode request,
                                    @RequestHeader(value = "Accept", required = false) String accept) {
        if (request == null || request.isMissingNode() || request.isNull()) {
            return json(errorEnvelope(-32600, "请求体为空"));
        }

        // 批量请求
        if (request.isArray()) {
            List<Map<String, Object>> responses = new ArrayList<>();
            for (JsonNode item : request) {
                Map<String, Object> response = mcpProtocolHandler.handle(item);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return ResponseEntity.accepted().build();
            }
            return sseOrJson(responses, accept);
        }

        Map<String, Object> response = mcpProtocolHandler.handle(request);
        if (response == null) {
            // 通知类消息：按协议返回 202 且不带响应体
            return ResponseEntity.accepted().build();
        }
        return sseOrJson(response, accept);
    }

    /**
     * 服务端主动推送流：本实现不提供，按协议返回 405
     */
    @SkAccess
    @GetMapping
    public ResponseEntity<?> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorEnvelope(-32601, "本服务端不提供 SSE 推送流，请使用 POST 调用"));
    }

    /**
     * 构造无 id 的 JSON-RPC 错误响应
     * <p>
     * 注意：不能用 Map.of 构造，其不允许 null 值（id 为空时会抛 NPE）。
     */
    private Map<String, Object> errorEnvelope(int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", null);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    /**
     * 会话关闭：无会话状态，按协议返回 204
     */
    @SkAccess
    @DeleteMapping
    public ResponseEntity<Void> close() {
        return ResponseEntity.noContent().build();
    }

    /**
     * 依据 Accept 决定响应形态：JSON 对象 或 SSE（单条 message 事件）
     * <p>
     * 注意：JSON 分支必须直接返回对象（交给消息转换器序列化），
     * 若返回已序列化的字符串会被再次转义，导致响应体变成 JSON 字符串而非对象。
     */
    private ResponseEntity<?> sseOrJson(Object payload, String accept) {
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            try {
                String json = MAPPER.writeValueAsString(payload);
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body("event: message\ndata: " + json + "\n\n");
            } catch (Exception e) {
                log.error("MCP 响应序列化失败", e);
                return ResponseEntity.internalServerError().build();
            }
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    private ResponseEntity<?> json(Object payload) {
        return sseOrJson(payload, null);
    }
}
