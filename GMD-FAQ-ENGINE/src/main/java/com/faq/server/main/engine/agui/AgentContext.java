package com.gmd.faq.server.main.engine.agui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.vo.AccountVO;
import io.agentscope.core.agui.model.RunAgentInput;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * 描述：智能体上下文
 *
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    private static final ThreadLocal<AgentContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private String threadId;
    private String runId;
    private boolean memoryActive;
    private boolean planActive;
    private List<String> fileIds;
    private AccountVO userInfo;
    private AgentDefinition agentDefinition;
    private Map<String, Object> params;
    private String tenantCode;
    private Long tenantId;
    /** 服务端鉴权快照，不从 forwardedProps 读取。 */
    private com.gmd.faq.server.main.common.UserDetail principal;
    private String runOwnerId;
    /** 本轮先只读调查并出方案；关闭按钮不会解除已存在的只读状态。 */
    private boolean planMode;

    public static void init(RunAgentInput input, String threadId) {
        // 每次请求创建全新的上下文，避免复用旧上下文导致租户信息串扰
        AgentContext agentContext = new AgentContext();

        agentContext.setThreadId(threadId);

        agentContext.setRunId(input.getRunId());

        boolean memoryActive = input.getForwardedProp("memoryActive") != null
                ? (Boolean) input.getForwardedProp("memoryActive")
                : false;
        agentContext.setMemoryActive(memoryActive);

        agentContext.setPlanActive(
                input.getForwardedProp("planActive") != null
                        ? (Boolean) input.getForwardedProp("planActive")
                        : false);

        agentContext.setFileIds(toList(input.getForwardedProp("fileIds")));
        agentContext.setPlanMode(Boolean.TRUE.equals(input.getForwardedProp("planMode")));

        AccountVO userInfo = input.getForwardedProp("userInfo") != null
                ? JsonUtils.objectToBean(input.getForwardedProp("userInfo"), AccountVO.class)
                : null;
        agentContext.setUserInfo(userInfo);

        agentContext.setParams(toMap(input.getForwardedProp("params")));

        init(agentContext);
    }

    public static void init(AgentContext agentContext) {
        AgentContextPropagation.initialize();
        CONTEXT_HOLDER.set(agentContext);
    }

    private static Map<String, Object> toMap(Object params) {
        if (params == null) {
            return new HashMap<>();
        }

        return JsonUtils.parse(JsonUtils.toJsonStr(params), new TypeReference<Map<String, Object>>() {});
    }

    private static List<String> toList(Object params) {
        if (params == null) {
            return new ArrayList<>();
        }
        return JsonUtils.parse(JsonUtils.toJsonStr(params), new TypeReference<List<String>>() {});
    }

    private static String toStr(Object params) {
        if (params == null) {
            return null;
        }
        return params instanceof String ? (String) params : params.toString();
    }

    public static AgentContext get() {
        AgentContext agentContext = CONTEXT_HOLDER.get();
        if (agentContext == null) {
            throw new IllegalStateException(
                    String.format("AgentContext not initialized for thread %s. " +
                                    "Please ensure init() is called before get().",
                            Thread.currentThread().getName())
            );
        }
        return agentContext;
    }

    public static Optional<AgentContext> getIfExists() {
        return Optional.ofNullable(CONTEXT_HOLDER.get());
    }

    public static void set(AgentContext agentContext) {
        AgentContextPropagation.initialize();
        CONTEXT_HOLDER.set(agentContext);
    }

    public static boolean exist() {
        return CONTEXT_HOLDER.get() != null;
    }

    public static void clean() {
        CONTEXT_HOLDER.remove();
    }
}
