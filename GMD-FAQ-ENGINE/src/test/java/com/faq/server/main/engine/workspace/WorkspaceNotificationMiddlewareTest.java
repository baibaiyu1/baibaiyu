package com.gmd.faq.server.main.engine.workspace;

import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.workspace.hook.*;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceNotificationMiddlewareTest {
    public static class WriteTool {
        @Tool(name = "write_text_file", description = "测试写工具")
        public String write() { return "written"; }
    }
    @Test void notificationWaitsForLegacyHooksAndIsSentOncePerBatch() { exercise(false); }
    @Test void deniedToolsDoNotSendFileNotifications() { exercise(true); }
    private void exercise(boolean denied) {
        List<String> order = new ArrayList<>();
        var notifier = mock(WebsocketHook.class);
        doAnswer(i -> { order.add("notify"); return null; }).when(notifier).sendFileChangeWs(eq("20"), isNull());
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            public String getModelName() { return "writes"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(calls.getAndIncrement() == 0 ? List.of(
                        ToolUseBlock.builder().id("first").name("write_text_file").input(Map.of()).build(),
                        ToolUseBlock.builder().id("second").name("write_text_file").input(Map.of()).build())
                        : List.of(TextBlock.builder().text("完成").build())).build());
            }
        };
        Hook compatibility = new Hook() {
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent) order.add("post");
                return Mono.just(event);
            }
        };
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(new WriteTool());
        var builder = ReActAgent.builder().name("writes").model(model).toolkit(toolkit).maxIters(3)
                .hooks(List.of(compatibility)).middleware(new WorkspaceNotificationMiddleware(notifier));
        if (denied) builder.permissionContext(PermissionContextState.builder().addDenyRule("write_text_file",
                new PermissionRule("write_text_file", null, PermissionBehavior.DENY, "test")).build());
        AgentContext owner = new AgentContext(); owner.setThreadId("20");
        var rc = RuntimeContext.builder().userId("u").sessionId("20").put(AgentContext.class, owner).build();
        try (var agent = builder.build()) {
            agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("写文件").build()), rc)
                    .collectList().block(Duration.ofSeconds(10));
        }
        if (denied) verify(notifier, never()).sendFileChangeWs(anyString(), any());
        else assertEquals(List.of("post", "post", "notify"), order);
    }
}
