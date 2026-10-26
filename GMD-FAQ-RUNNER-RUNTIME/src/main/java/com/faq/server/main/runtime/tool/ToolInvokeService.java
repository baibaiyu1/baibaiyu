package com.gmd.faq.server.main.runtime.tool;

import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.tool.IAgentTool;
import com.gmd.faq.server.main.engine.tool.ToolsRegister;
import com.gmd.faq.server.main.engine.tool.dynamices.IDynamicAgentTool;
import com.gmd.faq.server.main.engine.tool.dynamices.ToolInstanceLoadFactory;
import com.gmd.faq.server.main.runtime.endpoint.ToolReflectionInvoker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行 Service
 * <p>
 * 统一「内置工具（Java 反射调用）」与「自定义工具（Groovy 动态加载）」的执行入口，
 * 供调试端点 {@code /runtime/tool/{toolName}/do} 与 MCP 发布端点共用，避免两处实现漂移。
 *
 */
@Service
@RequiredArgsConstructor
public class ToolInvokeService {

    private final ToolReflectionInvoker toolReflectionInvoker;

    /**
     * 执行工具
     *
     * @param toolConfig 工具配置（来自数据库）
     * @param args       入参（按参数名取值）
     * @return 执行结果
     */
    public Object invoke(ToolConfig toolConfig, Map<String, Object> args) {
        Map<String, Object> params = args == null ? new LinkedHashMap<>() : args;
        if (toolConfig.getToolType() == ToolType.BUILTIN) {
            IAgentTool tool = ToolsRegister.getTool(toolConfig.getClassPath());
            return toolReflectionInvoker.invokeTool(tool, toolConfig.getToolId(), params);
        }
        IDynamicAgentTool dynamicAgentTool = ToolInstanceLoadFactory
                .getInstanceLoader(toolConfig.getLanguage())
                .loadInstance(toolConfig.getCode());
        AgentContext agentContext = new AgentContext();
        agentContext.setParams(Map.of());
        return dynamicAgentTool.execute(agentContext, params);
    }
}
