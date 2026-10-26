package com.gmd.faq.server.main.engine.agent;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.A2aAgent;
import lombok.Builder;
import lombok.Data;

/**
 * 描述：Agent Builder包装类
 *
 **/
@Data
@Builder
public class AgentBuilderWrapper {
    AgentDefinition definition;
    ReActAgent.Builder reactAgentBuilder;
    A2aAgent.Builder a2aAgentBuilder;
}
