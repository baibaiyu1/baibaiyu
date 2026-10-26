package com.gmd.faq.server.main.node.condition;

/**
 * 描述：分支执行结果
 *
 **/
public record ExecutionResult(String nextNodeId, boolean result) {
}
