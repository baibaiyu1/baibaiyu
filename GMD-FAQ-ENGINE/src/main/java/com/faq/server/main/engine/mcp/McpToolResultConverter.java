package com.gmd.faq.server.main.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;

/** 补充 SDK 未保留的资源链接和 structuredContent，交由统一图片归一化处理。 */
public final class McpToolResultConverter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private McpToolResultConverter() {}

    public static ToolResultBlock convert(McpSchema.CallToolResult result) {
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            return McpContentConverter.convertCallToolResult(result);
        }
        List<ContentBlock> blocks = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                ContentBlock block = content instanceof McpSchema.ResourceLink link
                        ? TextBlock.builder().text(MAPPER.valueToTree(link).toString()).build()
                        : McpContentConverter.convertContent(content);
                if (block != null) blocks.add(block);
            }
        }
        if (result.structuredContent() != null) {
            blocks.add(TextBlock.builder().text(MAPPER.valueToTree(result.structuredContent()).toString()).build());
        }
        return ToolResultBlock.of(blocks);
    }
}
