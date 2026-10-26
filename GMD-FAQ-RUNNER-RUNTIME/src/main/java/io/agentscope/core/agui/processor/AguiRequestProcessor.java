
package io.agentscope.core.agui.processor;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.engine.agui.AgentContextPropagation;
import com.gmd.faq.server.main.runtime.cluster.AgentSessionVersionService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import com.gmd.faq.server.main.common.util.AgentStateScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

public class AguiRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AguiRequestProcessor.class);

    private final AgentResolver agentResolver;
    private final AguiAdapterConfig config;
    private final AgentStateStore stateStore;
    private final JdbcTemplate jdbcTemplate;
    private final AgentSessionVersionService sessionVersionService;
    private final com.gmd.faq.server.main.engine.plan.AgentPlanService planService;
    private final Map<String, ResumeContext> temporaryContexts = new java.util.concurrent.ConcurrentHashMap<>();

    private AguiRequestProcessor(Builder builder) {
        this.agentResolver =
                Objects.requireNonNull(builder.agentResolver, "agentResolver cannot be null");
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.stateStore = builder.stateStore != null ? builder.stateStore : new InMemoryAgentStateStore();
        this.jdbcTemplate = builder.jdbcTemplate;
        this.sessionVersionService = builder.sessionVersionService;
        this.planService = builder.planService;
    }

    /**
     * Result of processing an AG-UI request.
     *
     * <p>Contains the resolved agent (for interrupt handling) and the event stream.
     *
     * @param agent The resolved agent instance
     * @param events The event stream
     */
    public record ProcessResult(Agent agent, Flux<AguiEvent> events, RuntimeContext runtimeContext) {}

    /**
     * Process an AG-UI request and return the result containing agent and event stream.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return A ProcessResult containing the agent and event stream
     */
    public ProcessResult process(RunAgentInput input, String headerAgentId, String pathAgentId) {
        String threadId = input.getThreadId();
        if (sessionVersionService != null) {
            sessionVersionService.ensureFresh(threadId);
        }

        // Resolve agent ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);

        // Resolve agent
        Agent agent = agentResolver.resolveAgent(agentId, threadId, runtimeContext(threadId, true).getUserId());

        // 添加threadId和租户信息
        if (agent instanceof AgentBase agentBase) {
            AgentContext current = AgentContext.get();
            Long tenantId = (Long) AgentMetadataStore.get(agentBase.getAgentId(), "tenantId");
            if (tenantId != null) {
                if (current.getTenantId() != null && !Objects.equals(current.getTenantId(), tenantId)) {
                    throw new IllegalStateException("智能体租户不匹配");
                }
                current.setTenantId(tenantId);
                current.setTenantCode((String) AgentMetadataStore.get(agentBase.getAgentId(), "tenantCode"));
            }
            if (current.getAgentDefinition() != null) {
                AgentMetadataStore.put(agentBase.getAgentId(), "agentDefinition", current.getAgentDefinition());
            } else {
                current.setAgentDefinition((AgentDefinition) AgentMetadataStore.get(agentBase.getAgentId(), "agentDefinition"));
            }
            if (AgentMetadataStore.get(agentBase.getAgentId(),"tenantId") == null) {
                AgentContext agentContext = AgentContext.get();
                AgentMetadataStore.put(agentBase.getAgentId(), "tenantId", agentContext.getTenantId());
                AgentMetadataStore.put(agentBase.getAgentId(), "tenantCode", agentContext.getTenantCode());
                AgentMetadataStore.put(agentBase.getAgentId(), "threadId", threadId);
            }

            TenantUtils.setCurrentTenant(current.getTenantId(), current.getTenantCode());
        }

        AgentContext current = AgentContext.get();
        // 项目智能体的运行选项由服务端定义统一决定；旧请求字段仅兼容外部 AgentResolver。
        AgentDefinition definition = current.getAgentDefinition();
        boolean memoryActive = definition != null ? Boolean.TRUE.equals(definition.getEnableMemory())
                : Boolean.TRUE.equals(input.getForwardedProp("memoryActive"));
        boolean toolProcessActive = definition != null ? Boolean.TRUE.equals(definition.getShowToolProcess())
                : Boolean.TRUE.equals(input.getForwardedProp("toolProcessActive"));
        if (definition != null) current.setPlanActive(Boolean.TRUE.equals(definition.getEnablePlanning()));
        AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", toolProcessActive);
        if (current.isPlanMode() && (planService == null || !(agent instanceof ReActAgent)
                || !com.gmd.faq.server.main.engine.plan.AgentPlanService.available(current)))
            throw new IllegalStateException("方案模式仅支持登录账号的本人正式自定义智能体会话");
        current.setMemoryActive(memoryActive);
        if (!threadId.matches("[0-9]+")) {
            temporaryContexts.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
            ResumeContext preview = new ResumeContext();
            preview.agentCode = agentId;
            preview.tenantId = current.getTenantId();
            preview.tenantCode = current.getTenantCode();
            preview.userId = current.getUserInfo() == null ? 0L : current.getUserInfo().getId();
            preview.expiresAt = System.currentTimeMillis() + 3600000L;
            temporaryContexts.put(threadId, preview);
        }

        RuntimeContext runtimeContext = runtimeContext(threadId, memoryActive);
        AgentMetadataStore.put(agent.getAgentId(), "stateUserId", runtimeContext.getUserId());
        RunAgentInput effectiveInput = input;
        if (memoryActive && agent instanceof ReActAgent react
                && !react.getAgentState(runtimeContext).getContext().isEmpty()) {
            effectiveInput = extractLatestUserMessage(input);
        }
        if (!memoryActive) {
            stateStore.delete(runtimeContext.getUserId(), threadId);
            if (agent instanceof ReActAgent react) react.clearStateCache(runtimeContext);
        }
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);
        Flux<AguiEvent> events = adapter.run(effectiveInput, runtimeContext)
                .concatMapIterable(event -> event instanceof AguiEvent.RunStarted
                        ? List.of(event, runOptionsEvent(threadId, input.getRunId(), current, toolProcessActive)) : List.of(event))
                .contextWrite(context -> AgentContextPropagation.withContext(context, runtimeContext.get(AgentContext.class)))
                .doOnTerminate(() -> finishState(agent, adapter, runtimeContext, memoryActive, toolProcessActive))
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) finishState(agent, adapter, runtimeContext, memoryActive, toolProcessActive);
                });
        return new ProcessResult(agent, events, runtimeContext);
    }

    private RuntimeContext runtimeContext(String threadId, boolean memoryActive) {
        AgentContext context = AgentContext.get();
        ResumeContext stored = loadResumeContext(threadId);
        Long tenantId = stored != null ? stored.tenantId : context.getTenantId();
        Long accountId = stored != null ? stored.userId
                : context.getUserInfo() != null ? context.getUserInfo().getId() : 0L;
        if (stored != null && !Objects.equals(stored.tenantId, context.getTenantId())) {
            throw new IllegalStateException("会话租户不匹配");
        }
        if (stored != null && !threadId.matches("[0-9]+") && context.getUserInfo() != null
                && !Objects.equals(stored.userId, context.getUserInfo().getId())) {
            throw new IllegalStateException("临时会话账号不匹配");
        }
        String userId = AgentStateScope.userId(tenantId, accountId);
        if (!memoryActive) userId += "_transient";
        return RuntimeContext.builder().userId(userId).sessionId(threadId)
                .put(AgentContext.class, context).build();
    }

    private void finishState(Agent agent, AguiAgentAdapter adapter, RuntimeContext context, boolean memoryActive, boolean toolProcessActive) {
        // AgentScope 2.0 在正常结束、暂停和停止时自动持久化当前槽位。
        if (adapter.isSuspended()) {
            AgentContext agentContext = context.get(AgentContext.class);
            stateStore.save(context.getUserId(), context.getSessionId(), "gmd_call_options",
                    new SavedCallOptions(agentContext != null && agentContext.isPlanActive(), memoryActive,
                            toolProcessActive));
        } else if (!memoryActive && context.getUserId().endsWith("_transient")) {
            deleteTransientState(agent, context);
        }
        // 从持久槽位暂停后关闭记忆，不删除已有历史；下次普通请求才使用临时槽位。
        if ((memoryActive || adapter.isSuspended() || !context.getUserId().endsWith("_transient")) && sessionVersionService != null)
            sessionVersionService.markPersisted(context.getSessionId());
    }

    public record SavedCallOptions(boolean planActive, Boolean memoryActive, Boolean toolProcessActive)
            implements io.agentscope.core.state.State {}

    private static AguiEvent runOptionsEvent(String threadId, String runId, AgentContext context, boolean toolProcessActive) {
        return new AguiEvent.Custom(threadId, runId, "CHAT_RUN_OPTIONS", Map.of(
                "memoryActive", context.isMemoryActive(), "planActive", context.isPlanActive(),
                "toolProcessActive", toolProcessActive));
    }

    private void deleteTransientState(Agent agent, RuntimeContext context) {
        for (String child : stateStore.listSessionIds(context.getUserId())) {
            if (child.startsWith(context.getSessionId() + ":subagent:")) stateStore.delete(context.getUserId(), child);
        }
        stateStore.delete(context.getUserId(), context.getSessionId());
        if (agent instanceof ReActAgent react) react.clearStateCache(context);
    }

    /**
     * HITL resume：根据用户确认决策恢复暂停的 agent。
     *
     * <p>全部允许 → agent 继续执行 pending 工具；含拒绝 → 喂入「用户已拒绝执行」结果后继续。
     * <p>跨实例安全：租户/agentId 从 chat_session 查（threadId 全局唯一），暂停态从分布式 Session 恢复。
     *
     * @param threadId 会话 ID（暂停态的 key）
     * @param decisions 逐工具决策（toolUseId/name/approved）；空表示全部允许
     * @param legacyMemoryActive 兼容旧客户端的参数，实际沿用服务端保存的暂停选项
     */
    public ProcessResult resume(String threadId, List<ResumeDecision> decisions, boolean legacyMemoryActive) {
        ResumeContext rc = loadResumeContext(threadId);
        if (rc == null) {
            throw new IllegalStateException("找不到会话或暂停态: " + threadId);
        }
        assertResumeScope(threadId, rc);
        TenantUtils.setCurrentTenant(rc.tenantId, rc.tenantCode);
        AgentContext.get().setTenantId(rc.tenantId);
        AgentContext.get().setTenantCode(rc.tenantCode);

        if (sessionVersionService != null) {
            sessionVersionService.ensureFresh(threadId);
        }
        Agent agent = agentResolver.resolveAgent(rc.agentCode, threadId, AgentStateScope.userId(rc.tenantId, rc.userId));
        AgentContext current = AgentContext.get();
        if (current.getAgentDefinition() == null) current.setAgentDefinition(
                (AgentDefinition) AgentMetadataStore.get(agent.getAgentId(), "agentDefinition"));
        // 重启后从工厂重建的智能体没有本轮元数据，图片、日志和工作区 Hook 均需要恢复。
        AgentMetadataStore.put(agent.getAgentId(), "tenantId", rc.tenantId);
        AgentMetadataStore.put(agent.getAgentId(), "tenantCode", rc.tenantCode);
        AgentMetadataStore.put(agent.getAgentId(), "threadId", threadId);
        AgentMetadataStore.put(agent.getAgentId(), "agentDefinition", current.getAgentDefinition());
        TenantUtils.setCurrentTenant(rc.tenantId, rc.tenantCode);
        String userId = AgentStateScope.userId(rc.tenantId, rc.userId);
        // 审批使用暂停时的槽位，不受续跑请求中记忆开关变化影响。
        String slotUser = stateStore.exists(userId + "_transient", threadId) ? userId + "_transient" : userId;
        SavedCallOptions saved = stateStore.get(slotUser, threadId, "gmd_call_options", SavedCallOptions.class).orElse(null);
        // 旧暂停态缺少记忆字段时按槽位恢复，避免覆盖原持久历史。
        boolean memoryActive = saved != null && saved.memoryActive() != null ? saved.memoryActive() : slotUser.equals(userId);
        AgentDefinition definition = current.getAgentDefinition();
        boolean toolProcessActive = saved != null && saved.toolProcessActive() != null ? saved.toolProcessActive()
                : definition != null && Boolean.TRUE.equals(definition.getShowToolProcess());
        current.setMemoryActive(memoryActive);
        current.setPlanActive(saved != null ? saved.planActive()
                : definition != null && Boolean.TRUE.equals(definition.getEnablePlanning()));
        AgentMetadataStore.put(agent.getAgentId(), "toolProcessActive", toolProcessActive);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(slotUser).sessionId(threadId)
                .put(AgentContext.class, AgentContext.get()).build();
        AgentMetadataStore.put(agent.getAgentId(), "stateUserId", runtimeContext.getUserId());
        if (!(agent instanceof ReActAgent react)) throw new IllegalStateException("当前智能体不支持工具审批续跑");
        react.clearStateCache(runtimeContext);
        var pending = pendingTools(react.getAgentState(runtimeContext));
        List<Msg> resumeInput = buildResumeInput(decisions, pending);
        for (ToolUseBlock tool : pending) {
            if (!"plan_exit".equals(tool.getName())) continue;
            boolean all = decisions == null || decisions.isEmpty();
            var decision = all ? new ResumeDecision(tool.getId(), tool.getName(), true) : decisions.stream()
                    .filter(item -> tool.getId().equals(item.toolUseId())).findFirst().orElse(null);
            if (decision != null) {
                if (planService == null) throw new IllegalStateException("方案审批服务不可用");
                planService.decide(current, tool.getId(), decision.approved());
            }
        }
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);
        boolean retainMemory = memoryActive;
        if (current.getRunId() == null) current.setRunId(UUID.randomUUID().toString());
        Flux<AguiEvent> events = adapter.runWithMessages(resumeInput, threadId, current.getRunId(), runtimeContext)
                .concatMapIterable(event -> event instanceof AguiEvent.RunStarted
                        ? List.of(event, runOptionsEvent(threadId, current.getRunId(), current, toolProcessActive)) : List.of(event))
                .contextWrite(context -> AgentContextPropagation.withContext(context, runtimeContext.get(AgentContext.class)))
                .doOnTerminate(() -> finishState(agent, adapter, runtimeContext, retainMemory, toolProcessActive))
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL) finishState(agent, adapter, runtimeContext, retainMemory, toolProcessActive);
                });
        return new ProcessResult(agent, events, runtimeContext);
    }

    /**
     * HITL 刷新恢复（配合前端刷新/重进会话）：从持久 Session 的暂停态重建「待确认工具」列表，
     * 供前端在没有内存态（RunTracker 已 markCompleted、或跨实例/重启）时重建确认 UI。
     *
     * @param threadId 会话 ID（暂停态的 key）
     * @return 待确认工具 [{toolUseId,name,input}]；无暂停态返回空列表
     */
    public List<Map<String, Object>> getPendingConfirms(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return List.of();
        }
        ResumeContext stored = loadResumeContext(threadId);
        if (stored == null) return List.of();
        assertResumeScope(threadId, stored);
        String userId = AgentStateScope.userId(stored.tenantId, stored.userId);
        String slotUser = stateStore.exists(userId + "_transient", threadId) ? userId + "_transient" : userId;
        return java.util.Optional.ofNullable(stateStore.getVersioned(slotUser, threadId, "agent_state", AgentState.class).value())
                .map(AguiRequestProcessor::pendingTools).orElse(List.of()).stream()
                .map(tool -> Map.<String, Object>of("toolUseId", tool.getId(), "name", tool.getName(), "input", tool.getInput()))
                .toList();
    }

    /**
     * 拒绝工具时回填的「错误结果」文案：用 error/不可用语义（而非中性「已拒绝」），
     * 降低 ReAct 模型把「拒绝」理解成「诉求未满足→重试」的倾向
     * （实测中性「用户已拒绝执行」会触发模型重调被拒工具）。
     * 注意：OpenAI/Ollama 协议无结构化 is_error，错误只能靠喂回的文本语义表达。
     */
    private static final String REJECT_RESULT_TEXT =
            "Error: 用户拒绝授权调用该工具，本轮对话中该工具不可用。请勿重试该工具，"
                    + "更不得自行编造、虚构或凭常识臆测该工具本应返回的结果数据；"
                    + "必须如实告知用户：因未获授权调用该工具，无法获取相关信息。";

    /** 核对暂停态中的工具 ID/名称，确认参数始终取服务端持久状态。 */
    static List<Msg> buildResumeInput(List<ResumeDecision> decisions, List<ToolUseBlock> pending) {
        if (pending.isEmpty()) throw new IllegalStateException("没有待确认的工具调用");
        Map<String, ToolUseBlock> byId = new LinkedHashMap<>();
        pending.forEach(tool -> byId.put(tool.getId(), tool));
        List<ResumeDecision> effective = decisions == null || decisions.isEmpty()
                ? pending.stream().map(tool -> new ResumeDecision(tool.getId(), tool.getName(), true)).toList() : decisions;
        List<ConfirmResult> confirmations = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResumeDecision decision : effective) {
            ToolUseBlock tool = decision == null ? null : byId.get(decision.toolUseId());
            if (tool == null || !Objects.equals(tool.getName(), decision.name()) || !seen.add(tool.getId())) {
                throw new IllegalArgumentException("审批决策与待确认工具不匹配或重复");
            }
            confirmations.add(new ConfirmResult(decision.approved(), tool, null,
                    decision.approved() ? null : REJECT_RESULT_TEXT));
        }
        return List.of(Msg.builder().role(MsgRole.USER)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations)).build());
    }

    static List<ToolUseBlock> pendingTools(AgentState state) {
        if (state == null) return List.of();
        List<Msg> context = state.getContext();
        java.util.Set<String> resolved = new java.util.HashSet<>();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            message.getContentBlocks(ToolResultBlock.class).forEach(result -> resolved.add(result.getId()));
            if (message.getRole() == MsgRole.ASSISTANT) {
                return message.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(tool -> tool.getState() == ToolCallState.ASKING && !resolved.contains(tool.getId())).toList();
            }
        }
        return List.of();
    }

    /** 从 chat_session 查 agentId + 租户（threadId 全局唯一，故不加租户过滤）。 */
    private ResumeContext loadResumeContext(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return null;
        }
        // 同上：threadId 可能是非数字的临时值，必须参数绑定 + 数字校验
        if (!threadId.chars().allMatch(Character::isDigit)) {
            ResumeContext stored = temporaryContexts.get(threadId);
            return stored != null && stored.expiresAt > System.currentTimeMillis() ? stored : null;
        }
        if (jdbcTemplate == null) return null;
        Long sessionId;
        try {
            sessionId = Long.valueOf(threadId);
        } catch (NumberFormatException e) {
            return null;
        }
        // resolveAgent 用 agentCode（registry 注册 key 是 agent_code，不是数字 id），故 JOIN gmd_faq_agent_definition 取 code
        String sql = String.format(
                "SELECT ad.agent_code AS agentCode, cs.tenant_id AS tenantId, cs.user_id AS userId, t.code AS tenantCode "
                        + "FROM %s cs JOIN %s t ON t.id = cs.tenant_id "
                        + "JOIN %s ad ON ad.id = cs.agent_id "
                        + "WHERE cs.id = ?",
                TableConst.CHAT_SESSION, TableConst.TENANT, TableConst.AGENT);
        List<ResumeContext> list = jdbcTemplate.query(sql, (rs, n) -> {
            ResumeContext c = new ResumeContext();
            c.agentCode = rs.getString("agentCode");
            c.tenantId = rs.getLong("tenantId");
            c.tenantCode = rs.getString("tenantCode");
            c.userId = rs.getLong("userId");
            return c;
        }, sessionId);
        return list.isEmpty() ? null : list.getFirst();
    }

    private static void assertResumeScope(String threadId, ResumeContext stored) {
        AgentContext current = AgentContext.get();
        if (!Objects.equals(stored.tenantId, current.getTenantId())
                || !threadId.matches("[0-9]+") && (current.getUserInfo() == null
                || !Objects.equals(stored.userId, current.getUserInfo().getId()))) {
            throw new IllegalStateException("会话归属不匹配");
        }
    }

    /** resume 逐工具决策。 */
    public record ResumeDecision(String toolUseId, String name, boolean approved) {}

    private static class ResumeContext {
        private String agentCode;
        private Long tenantId;
        private String tenantCode;
        private Long userId;
        private long expiresAt;
    }

    /**
     * 通过 sessionId 获取 AgentDefinition
     *
     * <p>注意：`threadId` 不保证是数字（智能体编排页的预览调试用
     * `thread_&lt;ts&gt;_&lt;rand&gt;` 这种临时值，见 {@code AguiRestController}）。
     * 这里必须走 JDBC 参数绑定而不是字符串拼接，否则临时 threadId 会被当成列名，
     * 直接报 `Unknown column 'thread_...' in 'where clause'`。
     *
     * @param sessionId threadId
     * @param jdbcTemplate jdbcTemplate
     */
    private AgentDefinition getAgentDefinition(String sessionId, JdbcTemplate jdbcTemplate) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        // 非数字的临时 threadId 一定查不到会话，直接短路，省一次无效查询
        if (!sessionId.chars().allMatch(Character::isDigit)) {
            return null;
        }

        Long sessionIdValue;
        try {
            sessionIdValue = Long.valueOf(sessionId);
        } catch (NumberFormatException e) {
            return null;
        }

        Long tenantId = TenantUtils.getCurrentTenantId();
        String chatSessionSql = "SELECT id, agent_id FROM " + TableConst.CHAT_SESSION + " WHERE id = ?"
                + (tenantId != null ? " AND tenant_id = ?" : "");
        List<ChatSession> chatSessions = tenantId != null
                ? jdbcTemplate.query(chatSessionSql, (rs, rowNum) -> {
                    ChatSession chatSession = new ChatSession();
                    chatSession.setId(rs.getLong("id"));
                    chatSession.setAgentId(rs.getLong("agent_id"));
                    return chatSession;
                }, sessionIdValue, tenantId)
                : jdbcTemplate.query(chatSessionSql, (rs, rowNum) -> {
                    ChatSession chatSession = new ChatSession();
                    chatSession.setId(rs.getLong("id"));
                    chatSession.setAgentId(rs.getLong("agent_id"));
                    return chatSession;
                }, sessionIdValue);

        if (chatSessions.isEmpty()) {
            return null;
        }

        Long agentId = chatSessions.getFirst().getAgentId();
        if (agentId == null) {
            return null;
        }

        String agentDefinitionSql = "SELECT id, enable_memory FROM " + TableConst.AGENT + " WHERE id = ?"
                + (tenantId != null ? " AND tenant_id = ?" : "");
        List<AgentDefinition> agentDefinitions = tenantId != null
                ? jdbcTemplate.query(agentDefinitionSql, (rs, rowNum) -> {
                    AgentDefinition agentDefinition = new AgentDefinition();
                    agentDefinition.setId(rs.getLong("id"));
                    agentDefinition.setEnableMemory(rs.getBoolean("enable_memory"));
                    return agentDefinition;
                }, agentId, tenantId)
                : jdbcTemplate.query(agentDefinitionSql, (rs, rowNum) -> {
                    AgentDefinition agentDefinition = new AgentDefinition();
                    agentDefinition.setId(rs.getLong("id"));
                    agentDefinition.setEnableMemory(rs.getBoolean("enable_memory"));
                    return agentDefinition;
                }, agentId);

        if (agentDefinitions.isEmpty()) {
            return null;
        }

        return agentDefinitions.getFirst();
    }

    /**
     * Resolve the agent ID from multiple sources.
     *
     * <p>The agent ID is resolved in the following priority order:
     * <ol>
     *   <li>URL path variable (if provided)</li>
     *   <li>HTTP header (if provided)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param input The request input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return The resolved agent ID
     */
    public String resolveAgentId(RunAgentInput input, String headerAgentId, String pathAgentId) {
        // 1. URL path variable has highest priority
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. Check HTTP header
        if (headerAgentId != null && !headerAgentId.isEmpty()) {
            logger.debug("Using agent ID from header: {}", headerAgentId);
            return headerAgentId;
        }

        // 3. Check forwardedProps for agentId
        Object agentIdProp = input.getForwardedProp("agentId");
        if (agentIdProp != null) {
            String propsAgentId = agentIdProp.toString();
            logger.debug("Using agent ID from forwardedProps: {}", propsAgentId);
            return propsAgentId;
        }

        // 4. Use config default
        if (config.getDefaultAgentId() != null) {
            logger.debug("Using default agent ID from config: {}", config.getDefaultAgentId());
            return config.getDefaultAgentId();
        }

        // 5. Fall back to "default"
        logger.debug("Using fallback agent ID: default");
        return "default";
    }

    /**
     * Extract only the latest user message from the input.
     *
     * <p>This is used when server-side memory is enabled and the agent already
     * has conversation history. Only the latest user message needs to be passed.
     *
     * @param input The original input
     * @return A new input with only the latest user message
     */
    public RunAgentInput extractLatestUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        // Find the last user message
        AguiMessage lastUserMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessage msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                lastUserMessage = msg;
                break;
            }
        }

        if (lastUserMessage == null) {
            return input;
        }

        // Create new input with only the last user message
        return RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(List.of(lastUserMessage))
                .tools(input.getTools())
                .context(input.getContext())
                .forwardedProps(input.getForwardedProps())
                .build();
    }

    /**
     * Creates a new builder for AguiRequestProcessor.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiRequestProcessor. */
    public static class Builder {

        private AgentResolver agentResolver;
        private AguiAdapterConfig config;
        private AgentStateStore stateStore;
        private JdbcTemplate jdbcTemplate;
        private AgentSessionVersionService sessionVersionService;
        private com.gmd.faq.server.main.engine.plan.AgentPlanService planService;

        public Builder planService(com.gmd.faq.server.main.engine.plan.AgentPlanService service) {
            this.planService = service; return this;
        }

        /**
         * Set the agent resolver.
         *
         * @param agentResolver The agent resolver
         * @return This builder
         */
        public Builder agentResolver(AgentResolver agentResolver) {
            this.agentResolver = agentResolver;
            return this;
        }

        /**
         * Set the adapter configuration.
         *
         * @param config The adapter configuration
         * @return This builder
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the session storage.
         *
         * @param session The session storage (InMemorySession or MysqlSession)
         * @return This builder
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * Set the jdbcTemplate storage.
         *
         * @param jdbcTemplate jdbcTemplate
         * @return This builder
         */
        public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            return this;
        }

        /**
         * 设置 Agent 会话版本管理服务。
         *
         * @param service 会话版本管理服务
         * @return 当前构建器
         */
        public Builder sessionVersionService(AgentSessionVersionService service) {
            this.sessionVersionService = service;
            return this;
        }

        /**
         * Build the processor.
         *
         * @return The built processor
         * @throws NullPointerException if agentResolver is not set
         */
        public AguiRequestProcessor build() {
            return new AguiRequestProcessor(this);
        }
    }
}
