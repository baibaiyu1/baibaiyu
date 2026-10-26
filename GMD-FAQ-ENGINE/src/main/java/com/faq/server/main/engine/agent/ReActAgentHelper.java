package com.gmd.faq.server.main.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.agent.service.CodeExecutionConfigService;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.CodeExecutionConfig;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.wrapper.KnowledgeWrapper;
import com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.hook.HooksFactory;
import com.gmd.faq.server.main.engine.hook.builtins.IConfirmationHook;
import com.gmd.faq.server.main.engine.knowledge.KnowledgeFactory;
import com.gmd.faq.server.main.engine.knowledge.KnowledgeRetrievalMiddleware;
import com.gmd.faq.server.main.engine.knowledge.KnowledgeRetrievalTool;
import com.gmd.faq.server.main.engine.knowledge.lightrag.LightRagKnowledge;
import com.gmd.faq.server.main.engine.model.ChatModelFactory;
import com.gmd.faq.server.main.engine.prompt.AgentSysPromptFactory;
import com.gmd.faq.server.main.engine.skill.SkillBoxFactory;
import com.gmd.faq.server.main.engine.skill.ReadOnlySkillMiddleware;
import com.gmd.faq.server.main.engine.memory.LongTermMemoryFactory;
import com.gmd.faq.server.main.engine.memory.ObservableAutoContextMemory;
import com.gmd.faq.server.main.engine.memory.SessionLongTermMemory;
import com.gmd.faq.server.main.engine.studio.StudioService;
import com.gmd.faq.server.main.engine.tool.ToolkitFactory;
import com.gmd.faq.server.main.engine.tool.ProjectToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述：ReAct智能体Helper
 *
 **/
@Component
@RequiredArgsConstructor
public class ReActAgentHelper {
    private final HooksFactory hooksFactory;
    private final ChatModelFactory chatModelFactory;
    private final AgentSysPromptFactory agentSysPromptFactory;
    private final SkillBoxFactory skillBoxFactory;
    private final ToolkitFactory toolkitFactory;
    private final KnowledgeFactory knowledgeFactory;
    private final StudioService studioService;
    private final LongTermMemoryFactory longTermMemoryFactory;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final CodeExecutionConfigService codeExecutionConfigService;
    private final com.gmd.faq.server.main.agent.service.AgentInstructionService instructionService;
    private final com.gmd.faq.server.main.engine.telemetry.AgentTelemetry telemetry;
    private final com.gmd.faq.server.main.agent.service.AgentModelResiliencePolicy modelResiliencePolicy;
    private final com.gmd.faq.server.main.agent.service.AgentConversationQueue conversationQueue;
    private final com.gmd.faq.server.main.engine.plan.AgentPlanService planService;

    @Autowired(required = false)
    private AgentStateStore stateStore = new InMemoryAgentStateStore();

    /**
     * 获取 ReActAgent
     * @param definition agent 定义
     */
    public ReActAgent getReActAgent(AgentDefinition definition) {
        // 构建reActAgent
        return getReactAgentBuilder(definition, AgentExecutionRole.ROOT).build();
    }

    /** Builds a delegated ReAct agent with the sub-agent execution policy applied. */
    public ReActAgent getReActAgentAsSubAgent(AgentDefinition definition) {
        return getReactAgentBuilder(definition, AgentExecutionRole.SUBAGENT).build();
    }

    /**
     * 获取 ReActAgent.Builder
     * @param definition  agent 定义
     */
    public ReActAgent.Builder getReactAgentBuilder(AgentDefinition definition) {
        return getReactAgentBuilder(definition, AgentExecutionRole.ROOT);
    }

    private ReActAgent.Builder getReactAgentBuilder(
            AgentDefinition definition, AgentExecutionRole executionRole) {
        Model model = chatModelFactory.getModel(definition);
        boolean showThinking = chatModelFactory.getReasoningSettings(definition).showThinking();
        Model summaryModel = telemetry.compaction(model, definition.getId());
        if (definition.getModelResilienceConfig() != null) {
            modelResiliencePolicy.validate(definition.getAgentType(), definition.getModelConfigId(), definition.getModelResilienceConfig(), definition.getModelParamsOverride());
            var settings = com.gmd.faq.server.main.common.model.ModelResilienceSettings.parse(definition.getModelResilienceConfig());
            Model backup = settings.fallbackModelId() == null ? null
                    : chatModelFactory.getAuxiliaryModel(definition, settings.fallbackModelId(), "MODEL");
            if (settings.summaryModelId() != null) summaryModel = chatModelFactory.getAuxiliaryModel(definition, settings.summaryModelId(), "COMPACTION");
            model = new com.gmd.faq.server.main.engine.model.ResilientModel(model, backup, settings.maxAttempts(), telemetry, definition.getId());
            summaryModel = new com.gmd.faq.server.main.engine.model.ResilientModel(summaryModel, null, settings.maxAttempts(), telemetry, definition.getId());
        }
        Toolkit toolkit = toolkitFactory.getToolkit(definition, executionRole);
        CodeExecutionConfig codeExecutionConfig = getCodeExecutionConfig(definition.getId());
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(definition.getAgentCode())
                .description(FuncUtils.isEmpty(definition.getDescription()) ? definition.getName() : definition.getDescription())
                .maxIters(definition.getMaxIterations())
                .model(model)
                .stateStore(stateStore)
                .defaultSessionId(AgentContext.get().getThreadId())
                .sysPrompt(agentSysPromptFactory.getAgentSysPrompt(definition, codeExecutionConfig != null))
                .toolkit(toolkit);
        var repository = skillBoxFactory.getRepository(definition, toolkit, codeExecutionConfig, executionRole);
        builder.skillRepository(repository).dynamicSkillsEnabled(false)
                .middleware(new ReadOnlySkillMiddleware(repository, toolkit, codeExecutionConfig != null));

        // ####### 知识库 #########
        List<KnowledgeWrapper> knowledgeWrappers = knowledgeFactory.getKnowledge(definition);
        if (!knowledgeWrappers.isEmpty()) {
            KnowledgeWrapper firstKnowledge = knowledgeWrappers.getFirst();
            int limit = firstKnowledge.getKnowledge() instanceof LightRagKnowledge
                    ? JsonUtils.getIntValue(firstKnowledge.getRetrievalConfig(), "topK", 20) : 5;
            var settings = KnowledgeRetrievalSettings.parse(definition.getRagConfig());
            RetrieveConfig retrieveConfig = RetrieveConfig.builder().limit(settings.topK() == null ? limit : settings.topK())
                    .scoreThreshold(settings.scoreThreshold() == null ? 0.5 : settings.scoreThreshold()).build();
            var retrievalTool = new KnowledgeRetrievalTool(knowledgeWrappers, retrieveConfig, settings);
            boolean automatic = definition.getRagConfig() != null && definition.getRagConfig().hasNonNull("ragMode")
                    ? "GENERIC".equals(settings.ragMode()) : firstKnowledge.getRagMode() == RAGMode.GENERIC;
            builder.middleware(new KnowledgeRetrievalMiddleware(retrievalTool, retrieveConfig, settings, automatic));
            if (!automatic) {
                toolkit.registerTool(retrievalTool);
                ((ProjectToolkit)toolkit).trustReadTools(KnowledgeRetrievalTool.NAME);
            }
        }


        // ####### 计划 #########
        if (Boolean.TRUE.equals(definition.getEnablePlanning())) {
            toolkit.registerTool(new AgentTaskTools(definition.getMaxSubtasks()));
            ((com.gmd.faq.server.main.engine.tool.ProjectToolkit)toolkit).trustControls("todo_write");
        }
        if (!executionRole.isSubAgent()) {
            var manager = planService.manager();
            toolkit.registerAgentTool(new io.agentscope.harness.agent.tool.PlanModeTools.PlanEnterTool(manager));
            toolkit.registerAgentTool(new io.agentscope.harness.agent.tool.PlanModeTools.PlanWriteTool(manager));
            toolkit.registerAgentTool(new io.agentscope.harness.agent.tool.PlanModeTools.PlanExitTool(manager));
            ((com.gmd.faq.server.main.engine.tool.ProjectToolkit)toolkit).trustControls("plan_enter", "plan_write", "plan_exit");
            builder.middleware(new com.gmd.faq.server.main.engine.plan.AgentPlanModeMiddleware(planService, manager, toolkit));
        }

        // 使用可变列表，避免 getHooks 返回 List.of() 时 add 抛 UnsupportedOperationException
        List<Hook> hooks = hooksFactory.getHooks(definition);
        hooks = hooks != null ? new ArrayList<>(hooks) : new ArrayList<>();
        hooks.removeIf(com.gmd.faq.server.main.engine.workspace.hook.WebsocketHook.class::isInstance);
        builder.middleware(hooksFactory.workspaceNotifications());
        if (executionRole.isSubAgent()) {
            hooks.removeIf(IConfirmationHook.class::isInstance);
        }

        LongTermMemory longTermMemory = longTermMemoryFactory.createLongTermMemory(definition);
        if (longTermMemory != null) {
            LongTermMemoryMode mode = longTermMemoryFactory.getMemoryMode(definition);
            if (mode == null || mode == LongTermMemoryMode.BOTH || mode == LongTermMemoryMode.AGENT_CONTROL) {
                toolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }
            if (mode == null || mode == LongTermMemoryMode.BOTH || mode == LongTermMemoryMode.STATIC_CONTROL) {
                builder.middleware(new SessionLongTermMemory(longTermMemory));
            }
        }

        var permissions = io.agentscope.core.permission.PermissionContextState.builder();
        for (String name : toolkit.getToolNames()) {
            permissions.addAllowRule(name, new io.agentscope.core.permission.PermissionRule(
                    name, null, io.agentscope.core.permission.PermissionBehavior.ALLOW, "gmd.configured-tool"));
        }
        if (!executionRole.isSubAgent() && hooks.stream().anyMatch(IConfirmationHook.class::isInstance)) {
            IConfirmationHook.rulesFor(toolkit).forEach(rule -> permissions.addAskRule(rule.toolName(), rule));
        }
        if (!executionRole.isSubAgent() && Boolean.TRUE.equals(definition.getEnablePlanning())
                && Boolean.TRUE.equals(definition.getRequirePlanConfirmation())) {
            permissions.addAskRule("todo_write", new io.agentscope.core.permission.PermissionRule(
                    "todo_write", null, io.agentscope.core.permission.PermissionBehavior.ASK, "gmd.plan"));
        }
        if (!executionRole.isSubAgent()) {
            permissions.addAskRule("plan_exit", new io.agentscope.core.permission.PermissionRule(
                    "plan_exit", null, io.agentscope.core.permission.PermissionBehavior.ASK, "gmd.plan-mode"));
        }
        var configuredPermissions = permissions.build();
        builder.permissionContext(configuredPermissions);
        builder.middleware(new AgentSessionPolicy(Boolean.TRUE.equals(definition.getEnablePlanning()), configuredPermissions));
        builder.middleware(new ReasoningDisplayMiddleware(showThinking));
        builder.middleware(new AgentRunLeaseMiddleware(conversationQueue));
        builder.middleware(new com.gmd.faq.server.main.engine.telemetry.AgentTelemetryMiddleware(telemetry,definition.getId(),!executionRole.isSubAgent()));
        if (!executionRole.isSubAgent()) builder.middleware(new AgentInstructionMiddleware(instructionService));

        // 配置记忆
        if (Boolean.TRUE.equals(definition.getEnableMemory())) {
            if (Boolean.TRUE.equals(definition.getEnableMemoryCompression())) {
                JsonNode config = definition.getMemoryCompressionConfig();
                builder.middleware(new ObservableAutoContextMemory(config, summaryModel));
                hooks.add(new ToolCallSequenceRepairHook());
            }
        }

        // 工具暂停态始终由 2.0 状态存储保存；记忆关闭时使用临时槽位。
        builder.enablePendingToolRecovery(true);

        // 配置Studio
        if (studioService.init(definition)) {
            hooks.add(new StudioMessageHook(StudioManager.getClient()));
        }

        // 添加Hook
        if (!hooks.isEmpty()) {
            builder.hooks(hooks);
        }

        // 2.0 的结构化输出由调用时传入的 schema 控制。

        // 保存Agent定义到上下文
        AgentContext.get().setAgentDefinition(definition);

        // 注册工具执行上下文
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(AgentContext.get())
                .build();
        builder.toolExecutionContext(context);

        return builder;
    }

    /**
     * 获取代码执行配置
     *
     * @param agentDefinitionId 智能体定义ID
     * @return 代码执行配置
     */
    private CodeExecutionConfig getCodeExecutionConfig(Long agentDefinitionId) {
        Long codeExecutionId = agentCodeExecutionService.getCodeExecutionIdByAgentId(agentDefinitionId);
        if (codeExecutionId == null) {
            return null;
        }
        return codeExecutionConfigService.getById(codeExecutionId);
    }
}
