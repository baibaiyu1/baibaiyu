package com.gmd.faq.server.main.engine.tool;

import io.agentscope.core.tool.*;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 只读资格绑定服务端实际注册的工具实例，名称或远端提示不能提升权限。 */
public final class ProjectToolkit extends Toolkit {
    private final Set<AgentTool> trustedReads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AgentTool> controls = Collections.newSetFromMap(new IdentityHashMap<>());

    public ProjectToolkit(ToolkitConfig config) { super(config); }

    public void trustReadTools(String... names) {
        for (String name : names) {
            AgentTool tool = getTool(name);
            if (tool != null) trustedReads.add(tool);
        }
    }
    public boolean isTrustedRead(AgentTool tool) { return trustedReads.contains(tool); }
    public void trustControls(String... names) {
        for (String name : names) {
            AgentTool tool = getTool(name);
            if (tool != null) controls.add(tool);
        }
    }
    public boolean isControl(AgentTool tool) { return controls.contains(tool); }
}
