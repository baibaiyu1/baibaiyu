package com.gmd.faq.server.main.node.agent;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 智能体节点执行结果。
 *
 */
@Getter
@Setter
public class AgentNodeResult {
    private String text;
    private Map<String, Object> structured;
    private String modelName;
    private Object usage;
    private Object generateReason;
}
