package com.gmd.faq.server.main.runtime.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.runtime.tool.ToolInvokeService;
import com.gmd.faq.server.main.tool.service.ToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议处理器（服务端）
 * <p>
 * 实现 MCP（Model Context Protocol）在「工具」维度的最小可用子集，供第三方 MCP 客户端接入：
 * <ul>
 *   <li>{@code initialize} —— 握手，回显客户端协议版本并声明 tools 能力</li>
 *   <li>{@code notifications/initialized} —— 客户端就绪通知（无响应）</li>
 *   <li>{@code ping} —— 连通性探测</li>
 *   <li>{@code tools/list} —— 列出本租户已发布（启用且属于组织公共资源池）的工具</li>
 *   <li>{@code tools/call} —— 执行工具，返回文本内容</li>
 * </ul>
 * 发布范围：仅本租户、已启用、可见性为「本组织可见」且未开启人工确认的工具；
 * 租户隔离由 SK 鉴权上下文 + MyBatis-Plus 租户拦截器共同保证。
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolHandler {

    /**
     * 客户端未声明协议版本时返回的默认版本
     */
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolService toolService;
    private final ToolInvokeService toolInvokeService;

    /**
     * 处理单条 JSON-RPC 消息
     *
     * @param request JSON-RPC 请求
     * @return 响应体；通知类消息（无 id）返回 null 表示无需响应
     */
    public Map<String, Object> handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            // 客户端通知（initialized / cancelled 等）无需响应
            return null;
        }
        String method = request.path("method").asText("");
        try {
            Map<String, Object> result = switch (method) {
                case "initialize" -> initialize(request);
                case "ping" -> Map.of();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(request);
                case "resources/list" -> Map.of("resources", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "服务端未实现的方法：" + method);
            }
            return success(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            log.error("MCP 请求处理失败，method={}", method, e);
            return error(id, -32603, "服务端内部错误：" + e.getMessage());
        }
    }

    /**
     * 握手：回显客户端协议版本（兼容优先），声明 tools 能力
     */
    private Map<String, Object> initialize(JsonNode request) {
        String clientVersion = request.path("params").path("protocolVersion").asText("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", clientVersion.isBlank() ? DEFAULT_PROTOCOL_VERSION : clientVersion);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of(
                "name", "gmd-faq-agent-platform",
                "title", "顶火智能体平台 · 工具服务",
                "version", "1.0.0"));
        return result;
    }

    /**
     * 列出已发布的工具
     */
    private Map<String, Object> listTools() {
        List<ToolConfig> tools = toolService.list(Wrappers.<ToolConfig>lambdaQuery()
                .eq(ToolConfig::getEnabled, true)
                // 仅发布已共享的平台资产，部门资产不通过无用户上下文的 MCP 端点暴露。
                // 归属人为个人的工具即使被设为部门共享，也只在其部门范围内可见，
                // 而本端点是**无用户上下文**的对外 MCP 服务，无法判定调用方部门，故一律不发布。
                .eq(ToolConfig::getOwnershipType, "PLATFORM")
                .eq(ToolConfig::getVisibility, "TENANT"));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolConfig tool : tools) {
            // 需人工确认的工具无法在 MCP 会话中完成确认流程，暂不对外发布
            if (Boolean.TRUE.equals(tool.getNeedConfirm())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getToolId());
            String description = tool.getDescription();
            item.put("description", description == null || description.isBlank() ? tool.getName() : description);
            item.put("inputSchema", buildInputSchema(tool));
            items.add(item);
        }
        return Map.of("tools", items);
    }

    /**
     * 调用工具：执行失败按 MCP 约定放在结果里（isError=true），而不是 JSON-RPC 错误
     */
    private Map<String, Object> callTool(JsonNode request) {
        JsonNode params = request.path("params");
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("缺少工具名称");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode arguments = params.path("arguments");
        if (arguments.isObject() && !arguments.isEmpty()) {
            args = MAPPER.convertValue(arguments, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }

        ToolConfig toolConfig = toolService.getOne(Wrappers.<ToolConfig>lambdaQuery()
                .eq(ToolConfig::getToolId, name)
                .eq(ToolConfig::getEnabled, true)
                // 与 tools/list 同口径：对外只服务组织公共资源池，个人资源不通过 MCP 端点暴露
                .eq(ToolConfig::getOwnershipType, "PLATFORM")
                .eq(ToolConfig::getVisibility, "TENANT"));
        if (toolConfig == null) {
            return toolResult("未找到可用工具：" + name, true);
        }
        if (Boolean.TRUE.equals(toolConfig.getNeedConfirm())) {
            return toolResult("该工具开启了人工确认，暂不支持通过 MCP 调用：" + name, true);
        }

        try {
            Object value = toolInvokeService.invoke(toolConfig, args);
            return toolResult(value instanceof String text ? text : JsonUtils.toJsonStr(value), false);
        } catch (Exception e) {
            log.warn("MCP 工具调用失败，tool={}", name, e);
            return toolResult("工具执行失败：" + e.getMessage(), true);
        }
    }

    /**
     * 平台工具参数（数组形式）转 MCP 需要的 JSON Schema
     */
    private Map<String, Object> buildInputSchema(ToolConfig tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        JsonNode schema = tool.getInputSchema();
        if (schema != null && schema.isArray()) {
            for (JsonNode node : schema) {
                String name = node.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", node.path("type").asText("string"));
                String description = node.path("description").asText("");
                property.put("description", description.isBlank() ? name : description);
                properties.put(name, property);
                if (node.path("required").asBoolean(false)) {
                    required.add(name);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        return result;
    }

    private Map<String, Object> toolResult(String text, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text == null ? "" : text)));
        result.put("isError", isError);
        return result;
    }

    private Map<String, Object> success(JsonNode id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", MAPPER.convertValue(id, Object.class));
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id == null ? null : MAPPER.convertValue(id, Object.class));
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
