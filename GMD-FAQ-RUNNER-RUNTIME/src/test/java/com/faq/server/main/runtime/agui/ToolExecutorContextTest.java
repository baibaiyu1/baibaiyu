package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorContextTest {
    @AfterEach void cleanup() { AgentContext.clean(); TenantUtils.clear(); }

    @Test void concurrentAsyncToolsRestoreRequestContextAndCallerOnSuccessAndError() {
        AgentContext caller = context(99L);
        AgentContext.set(caller);
        TenantUtils.setCurrentTenant(99L, "t99");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            public String getName() { return "context"; }
            public String getDescription() { return "context"; }
            public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                AgentContext expected = param.getRuntimeContext().get(AgentContext.class);
                assertSame(expected, AgentContext.get());
                assertEquals(expected.getTenantId(), TenantUtils.getCurrentTenantId());
                return Mono.delay(Duration.ofMillis(10)).map(ignored -> {
                    assertSame(expected, AgentContext.get());
                    assertEquals(expected.getTenantId(), TenantUtils.getCurrentTenantId());
                    if (expected.getTenantId() == 2L) throw new IllegalStateException("expected tool failure");
                    return ToolResultBlock.of(TextBlock.builder().text("tenant-" + expected.getTenantId()).build());
                });
            }
        });
        var results = Flux.merge(List.of(1L, 2L).stream().map(tenant -> {
            RuntimeContext rc = RuntimeContext.builder().userId("u" + tenant).sessionId("s" + tenant)
                    .put(AgentContext.class, context(tenant)).build();
            return toolkit.callTool(ToolCallParam.builder().runtimeContext(rc)
                    .toolUseBlock(new ToolUseBlock("c" + tenant, "context", Map.of(), "{}", Map.of())).build());
        }).toList()).collectList().block(Duration.ofSeconds(10));
        assertEquals(2, results.size());
        var text = results.stream().flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).toList();
        assertTrue(text.stream().anyMatch(value -> value.contains("tenant-1")), text.toString());
        assertTrue(text.stream().anyMatch(value -> value.contains("expected tool failure")), text.toString());
        assertSame(caller, AgentContext.get());
        assertEquals(99L, TenantUtils.getCurrentTenantId());
    }

    private static AgentContext context(Long tenant) {
        AgentContext context = new AgentContext(); context.setTenantId(tenant); context.setTenantCode("t" + tenant); return context;
    }
}
