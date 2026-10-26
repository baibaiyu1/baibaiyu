package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 仅控制对外输出和聊天日志，不能删掉供应商回放需要的 ThinkingBlock。 */
public record ReasoningDisplayMiddleware(boolean showThinking) implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AgentMetadataStore.put(agent.getAgentId(), "showThinking", showThinking);
            return next.apply(input);
        });
    }
}
