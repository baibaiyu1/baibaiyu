package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.AgentSubAgentService;
import com.gmd.faq.server.main.agent.service.CodeExecutionConfigService;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.engine.agent.A2aAgentHelper;
import com.gmd.faq.server.main.engine.agent.AgentExecutionRole;
import com.gmd.faq.server.main.engine.agent.ReActAgentHelper;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.mcp.McpClientFactory;
import com.gmd.faq.server.main.engine.tool.dynamices.DynamicAgentTool;
import com.gmd.faq.server.main.engine.tool.builtins.BochaWebSearchTool;
import com.gmd.faq.server.main.engine.tool.builtins.WebPageFetchTool;
import com.gmd.faq.server.main.engine.workspace.tool.SearchReplaceFileTool;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.gmd.faq.server.main.tool.service.ToolService;
import com.gmd.faq.server.main.workflowbiz.core.WorkflowDefinitionCompiler;
import com.gmd.faq.server.main.workflowbiz.service.AgentWorkflowService;
import com.gmd.faq.server.main.workflowbiz.service.WorkflowRunService;
import com.gmd.faq.server.main.workflowbiz.service.WorkflowService;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 描述：工具工厂
 *
 */
@Slf4j
@Component
public class ToolkitFactory {
    private final ToolService toolService;
    private final AgentToolService agentToolService;
    private final AgentSubAgentService agentSubAgentService;
    private final ReActAgentHelper reActAgentHelper;
    private final A2aAgentHelper a2aAgentHelper;
    private final McpClientFactory mcpClientFactory;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final CodeExecutionConfigService codeExecutionConfigService;
    private final CustomToolkitConfig customToolkitConfig;
    private final AgentWorkflowService agentWorkflowService;
    private final WorkflowService workflowService;
    private final WorkflowRunService workflowRunService;
    private final WorkflowDefinitionCompiler workflowDefinitionCompiler;
    private final DocumentTextAccess documentTextAccess;

    public ToolkitFactory(ToolService toolService,
                          AgentToolService agentToolService,
                          AgentSubAgentService agentSubAgentService,
                          @Lazy
                          ReActAgentHelper reActAgentHelper,
                          @Lazy
                          A2aAgentHelper a2aAgentHelper,
                          McpClientFactory mcpClientFactory,
                          AgentCodeExecutionService agentCodeExecutionService,
                          CodeExecutionConfigService codeExecutionConfigService,
                          AgentDefinitionService agentDefinitionService,
                          AgentWorkflowService agentWorkflowService,
                          WorkflowService workflowService,
                          WorkflowRunService workflowRunService,
                          WorkflowDefinitionCompiler workflowDefinitionCompiler,
                          CustomToolkitConfig customToolkitConfig,
                          DocumentTextAccess documentTextAccess) {
        this.toolService = toolService;
        this.agentToolService = agentToolService;
        this.agentSubAgentService = agentSubAgentService;
        this.reActAgentHelper = reActAgentHelper;
        this.a2aAgentHelper = a2aAgentHelper;
        this.mcpClientFactory = mcpClientFactory;
        this.agentCodeExecutionService = agentCodeExecutionService;
        this.codeExecutionConfigService = codeExecutionConfigService;
        this.agentDefinitionService = agentDefinitionService;
        this.agentWorkflowService = agentWorkflowService;
        this.workflowService = workflowService;
        this.workflowRunService = workflowRunService;
        this.workflowDefinitionCompiler = workflowDefinitionCompiler;
        this.customToolkitConfig = customToolkitConfig;
        this.documentTextAccess = documentTextAccess;
    }


    public Toolkit getToolkit(AgentDefinition agentDefinition) {
        return getToolkit(agentDefinition, AgentExecutionRole.ROOT);
    }

    /**
     * Builds a toolkit for an agent execution role. A delegated sub-agent must not receive a
     * tool that is configured to pause for human confirmation.
     */
    public Toolkit getToolkit(AgentDefinition agentDefinition, AgentExecutionRole executionRole) {
        List<Long> toolIds = agentToolService.getToolIds(agentDefinition.getId());
        Toolkit toolkit = createToolkit();

        if (!toolIds.isEmpty()) {
            registerTools(toolkit, toolService.listByIds(toolIds), executionRole);
        }

        // 注册工作流
        List<Long> workflowIds = agentWorkflowService.getWorkflowIds(agentDefinition.getId());
        if (!workflowIds.isEmpty()) {
            registerWorkflows(toolkit, workflowIds);
        }


        // 注册文件搜索替换工具
        Long codeExecutionId = agentCodeExecutionService.getCodeExecutionIdByAgentId(agentDefinition.getId());
        if (codeExecutionId != null) {
            CodeExecutionConfig config = codeExecutionConfigService.getById(codeExecutionId);
            if (config != null && Boolean.TRUE.equals(config.getEnabled()) && Boolean.TRUE.equals(config.getEnableWrite())) {
                toolkit.registerTool(new SearchReplaceFileTool());
            }
        }

        // 此处仅注册缓存的 MCP 工具模式，真正的 MCP 连接会在调用时打开。
        mcpClientFactory.getLazyMcpTools(agentDefinition).stream()
                .filter(tool -> allowToolInRole(tool, executionRole))
                .map(PermissionAwareTool::new)
                .forEach(toolkit::registerAgentTool);

        // 注册 Agent as Tool
        List<Long> subAgentIds = agentSubAgentService.getSubAgentIds(agentDefinition.getId());
        if (!subAgentIds.isEmpty()) {
            registerSubAgents(toolkit, subAgentIds, executionRole);
        }

        // 注册文档附件文本读取工具（所有智能体无条件注册）
        toolkit.registerTool(new LoadFileTextContentTool(documentTextAccess));
        ((ProjectToolkit) toolkit).trustReadTools("load_file_text_content");

        return toolkit;
    }

    public Toolkit getToolkit(List<Long> toolIds) {
        Toolkit toolkit = createToolkit();
        if (!toolIds.isEmpty()) {
            registerTools(toolkit, toolService.listByIds(toolIds), AgentExecutionRole.ROOT);
        }
        return toolkit;
    }

    private Toolkit createToolkit() {
        // 是否允许并行执行多个工具
        // 是否允许删除工具
        // 设置工具执行超时时间
        return new ProjectToolkit(
                ToolkitConfig.builder()
                        // 是否允许并行执行多个工具
                        .parallel(customToolkitConfig.isParallel())
                        // 是否允许删除工具
                        .allowToolDeletion(customToolkitConfig.isAllowToolDeletion())
                        // 设置工具执行超时时间
                        .executionConfig(customToolkitConfig.toExecutionConfig())
                        .build());
    }

    /**
     * 注册工具
     *
     * @param toolkit     工具集
     * @param toolConfigs 工具配置列表
     */
    public static void registerTools(Toolkit toolkit, List<ToolConfig> toolConfigs) {
        registerTools(toolkit, toolConfigs, AgentExecutionRole.ROOT);
    }

    /** Registers configured tools, excluding confirmation-gated tools for sub-agents. */
    public static void registerTools(
            Toolkit toolkit, List<ToolConfig> toolConfigs, AgentExecutionRole executionRole) {
        if (toolConfigs == null || toolConfigs.isEmpty()) {
            return;
        }

        toolConfigs.stream()
                .filter(ToolConfig::getEnabled)
                .filter(toolConfig -> {
                    boolean blocked = executionRole.isSubAgent()
                            && Boolean.TRUE.equals(toolConfig.getNeedConfirm());
                    if (blocked) {
                        log.warn("Skip confirmation-gated tool '{}' for delegated sub-agent execution", toolConfig.getToolId());
                    }
                    return !blocked;
                })
                .forEach(toolConfig -> {
                    AgentTool tool = toolkit.getTool(toolConfig.getToolId());
                    // 工具不存在时
                    if (tool == null) {
                        // 内置工具注册
                        if (toolConfig.getToolType() == ToolType.BUILTIN) {
                            IAgentTool builtin = ToolsRegister.getTool(toolConfig.getClassPath());
                            toolkit.registerTool(builtin);
                            AgentTool registered = toolkit.getTool(toolConfig.getToolId());
                            if (registered != null) {
                                toolkit.registerAgentTool(new PermissionAwareTool(registered,
                                        toolConfig.getReturnDirect(), Boolean.TRUE.equals(toolConfig.getNeedConfirm())));
                                // 只信任服务端实际注册的这两个内置实例；同名动态工具和 MCP 不继承只读资格。
                                if (toolkit instanceof ProjectToolkit project
                                        && (builtin instanceof BochaWebSearchTool || builtin instanceof WebPageFetchTool)) {
                                    project.trustReadTools(registered.getName());
                                }
                            }
                        } else {
                            // 动态工具注册
                            toolkit.registerAgentTool(new PermissionAwareTool(new DynamicAgentTool(toolConfig),
                                    toolConfig.getReturnDirect(), Boolean.TRUE.equals(toolConfig.getNeedConfirm())));
                        }
                    }
                });
    }

    /**
     * 注册工作流
     *
     * @param toolkit     工具集
     * @param workflowIds 工作流ID
     */
    public void registerWorkflows(Toolkit toolkit, List<Long> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return;
        }

        List<Workflow> workflows = workflowService.listByIds(workflowIds);
        workflows.stream()
                .filter(Workflow::getEnabled)
                .forEach(workflow -> {
                    WorkflowVersion workflowVersion = workflowService.latestPublishedVersion(workflow.getId());
                    if (workflowVersion != null) {
                        toolkit.registerTool(new WorkflowTool(
                                workflow,
                                workflowVersion,
                                workflowRunService,
                                workflowDefinitionCompiler)
                        );
                    }
                });
    }

    private void registerSubAgents(
            Toolkit toolkit, List<Long> subAgentIds, AgentExecutionRole parentExecutionRole) {
        for (Long subAgentId : subAgentIds) {
            AgentDefinition definition = agentDefinitionService.getById(subAgentId);

            if (definition == null || !definition.getEnabled()) {
                continue;
            }

            try {
                // Agent as Tool
                switch (definition.getAgentType()) {
                    case CUSTOM:
                        toolkit.registration()
                                .subAgent(() -> reActAgentHelper.getReActAgentAsSubAgent(definition),
                                        createSubAgentConfig(definition, parentExecutionRole))
                                .apply();
                        break;
                    case A2A:
                        toolkit.registration()
                                .subAgent(() -> a2aAgentHelper.getA2aAgent(definition),
                                        createSubAgentConfig(definition, parentExecutionRole))
                                .apply();
                        break;
                    default:
                        break;
                }
                log.debug("Register sub agent: {}", subAgentId);
            } catch (Exception e) {
                log.error("Registration of sub agent failed: {}", subAgentId, e);
            }
        }
    }

    private SubAgentConfig createSubAgentConfig(AgentDefinition definition, AgentExecutionRole parentExecutionRole) {
        return SubAgentConfig.builder()
                .toolName(definition.getAgentCode().toLowerCase())
                .description(definition.getDescription() != null ?
                        definition.getDescription() : definition.getName())
                // 是否转发事件, 子Agent的子Agent不进行事件转发，前端也只显示一级子Agent的卡片
                .forwardEvents(!parentExecutionRole.isSubAgent())
                .build();
    }

    static boolean allowToolInRole(AgentTool tool, AgentExecutionRole executionRole) {
        boolean blocked = executionRole.isSubAgent()
                && tool instanceof DelegatingToolBase wrapped && wrapped.requiresConfirmation();
        if (blocked) log.warn("Skip confirmation-gated MCP tool '{}' for delegated sub-agent execution", tool.getName());
        return !blocked;
    }
}
