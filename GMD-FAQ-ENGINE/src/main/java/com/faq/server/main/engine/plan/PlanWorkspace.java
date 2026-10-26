package com.gmd.faq.server.main.engine.plan;

import com.gmd.faq.server.main.engine.agui.AgentContext;

/** 复用产品工作区的路径、容量与文件访问规则。 */
public interface PlanWorkspace {
    void write(AgentContext owner, String path, String content);
    String read(AgentContext owner, String path);
}
