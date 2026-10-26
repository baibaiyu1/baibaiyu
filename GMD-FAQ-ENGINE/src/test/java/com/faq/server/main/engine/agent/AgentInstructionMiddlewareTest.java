package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.agent.service.AgentInstructionService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.*;
import io.agentscope.core.middleware.AgentInput;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.mockito.Mockito.*;

class AgentInstructionMiddlewareTest {
    @Test void temporaryAndMissingIdentityNeverAccessInbox() {
        var service = mock(AgentInstructionService.class);
        var middleware = new AgentInstructionMiddleware(service);
        AgentContext owner = new AgentContext(); owner.setTenantId(1L); owner.setThreadId("preview"); owner.setRunId("run");
        AccountVO user = new AccountVO(); user.setId(2L); owner.setUserInfo(user);
        AgentDefinition definition = new AgentDefinition(); definition.setId(4L); owner.setAgentDefinition(definition);
        var rc = RuntimeContext.builder().userId("u").sessionId("s").put(AgentContext.class,owner).build();
        middleware.onAgent(mock(Agent.class),rc,new AgentInput(List.of()), ignored -> Flux.empty()).blockLast(Duration.ofSeconds(5));
        owner.setThreadId("3"); owner.setUserInfo(null);
        middleware.onAgent(mock(Agent.class),rc,new AgentInput(List.of()), ignored -> Flux.empty()).blockLast(Duration.ofSeconds(5));
        verifyNoInteractions(service);
    }
}
