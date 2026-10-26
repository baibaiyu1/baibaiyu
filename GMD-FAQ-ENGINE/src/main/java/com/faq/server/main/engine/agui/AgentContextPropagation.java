package com.gmd.faq.server.main.engine.agui;

import com.gmd.faq.server.main.common.util.TenantUtils;
import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;
import reactor.util.context.Context;

/** 在 Reactor 调度和订阅边界恢复上下文，退出边界时还原线程原值。 */
public final class AgentContextPropagation {
    private static final String AGENT_KEY = "gmd.agent.context";
    private static final String TENANT_KEY = "gmd.tenant.context";
    private static final String USER_KEY = "gmd.user.context";

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(AGENT_KEY,
                () -> AgentContext.getIfExists().orElse(null), AgentContext::set, AgentContext::clean);
        ContextRegistry.getInstance().registerThreadLocalAccessor(TENANT_KEY,
                () -> TenantUtils.getCurrentTenantId() == null ? null
                        : new Tenant(TenantUtils.getCurrentTenantId(), TenantUtils.getCurrentTenantCode()),
                tenant -> TenantUtils.setCurrentTenant(tenant.id(), tenant.code()), TenantUtils::clear);
        ContextRegistry.getInstance().registerThreadLocalAccessor(USER_KEY,
                com.gmd.faq.server.main.common.util.UserUtils::getBackgroundUserDetail,
                com.gmd.faq.server.main.common.util.UserUtils::setBackgroundUserDetail,
                () -> com.gmd.faq.server.main.common.util.UserUtils.setBackgroundUserDetail(null));
        Hooks.enableAutomaticContextPropagation();
    }

    private AgentContextPropagation() {}

    /** 应在创建 Reactor 执行链前初始化，确保调度器和 block 的上下文捕获使用同一组 accessor。 */
    public static void initialize() {}

    public static Context withContext(Context context, AgentContext agent) {
        if (agent == null) return context.delete(AGENT_KEY).delete(TENANT_KEY).delete(USER_KEY);
        Context bound = context.put(AGENT_KEY, agent);
        bound = agent.getPrincipal() == null ? bound.delete(USER_KEY) : bound.put(USER_KEY, agent.getPrincipal());
        return agent.getTenantId() == null ? bound.delete(TENANT_KEY)
                : bound.put(TENANT_KEY, new Tenant(agent.getTenantId(), agent.getTenantCode()));
    }

    private record Tenant(Long id, String code) {}
}
