package com.gmd.faq.server.main.mcp.config.impl;

import com.gmd.faq.server.main.common.entity.McpServer;
import com.gmd.faq.server.main.common.enums.McpMode;
import com.gmd.faq.server.main.common.enums.McpProtocol;
import com.gmd.faq.server.main.common.key.McpSseKey;
import com.gmd.faq.server.main.mcp.config.McpClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.time.Duration;
import java.util.Map;

/**
 * 描述：Sse MCP客户端配置
 *
 **/
public class SseMcpClientConfig implements McpClientConfig {
    @Override
    public McpClientWrapper getMcpClient(McpServer mcpServer) {
        if (mcpServer.getProtocol() != protocol()) {
            throw new IllegalArgumentException("McpServer protocol must be SSE");
        }

        // 解析配置
        JsonNode config = mcpServer.getProtocolConfig();

        // 构建MCP客户端
        McpClientBuilder builder = McpClientBuilder.create(mcpServer.getName())
                .sseTransport(config.get(McpSseKey.url).asText())
                .queryParams(getQueryParams(config))
                .headers(getHeaders(config))
                .timeout(Duration.ofSeconds(mcpServer.getTimeout()))
                .initializationTimeout(Duration.ofSeconds(30));

        // 构建MCP客户端并返回
        if (mcpServer.getMode() == McpMode.SYNC) {
            return builder.buildSync();
        } else {
            return builder.buildAsync().block();
        }
    }

    /**
     * 获取请求参数
     *
     * @param config 配置
     */
    private Map<String, String> getQueryParams(JsonNode config) {
        JsonNode jsonNode = config.get(McpSseKey.queryParams);
        return jsonNode2Map(jsonNode);
    }

    /**
     * 获取请求参数
     *
     * @param config 配置
     */
    private Map<String, String> getHeaders(JsonNode config) {
        JsonNode jsonNode = config.get(McpSseKey.headers);
        return jsonNode2Map(jsonNode);
    }

    @Override
    public McpProtocol protocol() {
        return McpProtocol.SSE;
    }
}
