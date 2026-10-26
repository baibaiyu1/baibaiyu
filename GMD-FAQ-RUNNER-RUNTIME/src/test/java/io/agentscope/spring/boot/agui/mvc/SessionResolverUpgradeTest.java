package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class SessionResolverUpgradeTest {
    @Test void nativeResolverUsesUserSlotsAndBusinessEvictionClearsAllSlots() {
        var sessions = new ThreadSessionManager(100, 30);
        var registry = new AguiAgentRegistry();
        Model model = new Model() {
            public String getModelName() { return "fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("done").build())).build());
            }
        };
        var store = new InMemoryAgentStateStore();
        registry.registerFactory("fixture", () -> ReActAgent.builder().name("fixture").model(model).stateStore(store).build());
        var resolver = new DefaultAgentResolver(registry, sessions, true);
        var first = resolver.resolveAgent("fixture", "s", "tenant_1_user_2");
        var second = resolver.resolveAgent("fixture", "s", "tenant_2_user_2");
        assertNotSame(first, second);
        assertSame(first, resolver.resolveAgent("fixture", "s", "tenant_1_user_2"));
        assertTrue(sessions.getSession("s").isPresent());
        var rc = RuntimeContext.builder().userId("tenant_1_user_2").sessionId("s").build();
        ((ReActAgent) first).call(List.of(Msg.builder().role(MsgRole.USER).textContent("hi").build()), rc).block();
        assertTrue(resolver.hasMemory(rc));
        assertFalse(resolver.hasMemory(RuntimeContext.builder().userId("tenant_2_user_2").sessionId("s").build()));
        assertTrue(sessions.evict("s"));
        assertEquals(0, sessions.getSessionCount());
        ((ReActAgent) first).close();
        ((ReActAgent) second).close();
    }
}
