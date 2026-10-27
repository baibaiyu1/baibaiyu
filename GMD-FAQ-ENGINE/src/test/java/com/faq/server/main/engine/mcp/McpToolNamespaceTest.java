package com.gmd.faq.server.main.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.DelegatingToolBase;
import com.gmd.faq.server.main.mcp.service.McpRuntimeDegradeService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolNamespaceTest {
    @Test void sameRemoteNamesRemainDistinctAndCallsUseTheOriginalRemoteName() throws Exception {
        var mapper = new ObjectMapper();
        var schema = mapper.readValue("{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}", McpSchema.Tool.class);
        var firstClient = mock(McpClientWrapper.class); var secondClient = mock(McpClientWrapper.class);
        var response = mapper.readValue("{\"content\":[{\"type\":\"text\",\"text\":\"result\"}],\"isError\":false}", McpSchema.CallToolResult.class);
        when(firstClient.callTool("search", Map.of())).thenReturn(Mono.just(response));
        when(secondClient.callTool("search", Map.of())).thenReturn(Mono.just(response));
        var toolkit = new Toolkit();
        for (long serverId : List.of(41L, 42L)) {
            var client = serverId == 41L ? firstClient : secondClient;
            var tool = new LazyMcpAgentTool(new LazyMcpAgentTool.RuntimeDegradeContext(serverId, "server" + serverId, 1L, "hash", 3),
                    schema, () -> Mono.just(client), mock(McpRuntimeDegradeService.class));
            toolkit.registerAgentTool(new com.gmd.faq.server.main.engine.tool.PermissionAwareTool(tool, null, serverId == 41L));
        }
        assertEquals(2, toolkit.getToolNames().size());
        assertTrue(((DelegatingToolBase) toolkit.getTool("mcp_41_search")).requiresConfirmation());
        assertFalse(((DelegatingToolBase) toolkit.getTool("mcp_42_search")).requiresConfirmation());
        var owner = new AgentContext(); owner.setTenantId(1L); owner.setTenantCode("test");
        var context = RuntimeContext.builder().sessionId("audit").put(AgentContext.class, owner).build();
        for (String name : toolkit.getToolNames()) toolkit.getTool(name).callAsync(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id(name).name(name).input(Map.of()).build()).input(Map.of()).runtimeContext(context).build()).block();
        verify(firstClient).callTool("search", Map.of()); verify(secondClient).callTool("search", Map.of());
    }
    @Test void longAndNonAsciiNamesAreValidStableAndDistinct() {
        for (String name : List.of("工具查询", "a".repeat(100), "a".repeat(99) + "b")) {
            String actual = LazyMcpAgentTool.runtimeName(41L, name);
            assertTrue(actual.matches("[A-Za-z0-9_-]{1,64}"));
            assertEquals(actual, LazyMcpAgentTool.runtimeName(41L, name));
        }
        assertNotEquals(LazyMcpAgentTool.runtimeName(41L, "工具查询"), LazyMcpAgentTool.runtimeName(41L, "____"));
        assertNotEquals(LazyMcpAgentTool.runtimeName(41L, "a".repeat(100)), LazyMcpAgentTool.runtimeName(41L, "a".repeat(99) + "b"));
    }
}
