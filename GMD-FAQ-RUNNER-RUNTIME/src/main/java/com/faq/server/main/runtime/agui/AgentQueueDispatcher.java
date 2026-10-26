package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.account.service.AccountService;
import com.gmd.faq.server.main.account.service.TenantService;
import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.config.auth.PermsResolver;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.dto.ChatMessageAppendDTO;
import com.gmd.faq.server.main.common.util.*;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 关闭网页后仍按序执行；每次调度重新解析账号与资源权限，不保存登录令牌。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentQueueDispatcher {
    private final AgentConversationQueue queue;
    private final AgentRunCoordinator coordinator;
    private final AguiMvcController agui;
    private final AccountService accounts;
    private final TenantService tenants;
    private final PermsResolver perms;
    private final SessionAccessGuard sessions;
    private final ChatSessionService chats;
    private final AgentDefinitionService agents;
    private final ResourceVisibilityService visibility;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        for (var scope:queue.candidates(coordinator.nodeId())) {
            if (coordinator.activeCount() >= 4) return;
            try {
                var tenant=tenants.getById(scope.tenantId());
                if (tenant==null || !Boolean.TRUE.equals(tenant.getEnabled())) throw new IllegalStateException("租户已失效");
                TenantUtils.setCurrentTenant(tenant.getId(),tenant.getCode());
                var account=accounts.getById(scope.userId());
                if (account==null || !Boolean.TRUE.equals(account.getEnabled()) || Boolean.TRUE.equals(account.getMustChangePassword()))
                    throw new IllegalStateException("账号已失效或需要修改密码");
                Long membership=jdbc.queryForObject("SELECT COUNT(*) FROM "+TableConst.ACCOUNT_TENANT+" WHERE tenant_id=? AND account_id=? AND enabled=1",Long.class,scope.tenantId(),scope.userId());
                if (membership==null || membership==0) throw new IllegalStateException("账号已不属于该租户");
                var access=perms.resolveAccess(scope.userId(),scope.tenantId());
                var principal=UserDetail.builder().id(scope.userId()).username(account.getUsername()).name(account.getNickname())
                        .tenantId(scope.tenantId()).tenantCode(tenant.getCode()).authType(UserUtils.AUTH_ACCOUNT)
                        .perms(access.perms()).deptId(access.deptId()).dataScope(access.dataScope().name()).build();
                UserUtils.withUserDetail(principal,() -> { start(scope); return null; });
            } catch (RuntimeException error) {
                queue.pause(scope);
                log.warn("排队任务暂停 session={} type={}",scope.sessionId(),error.getClass().getSimpleName());
            } finally { TenantUtils.clear(); }
        }
    }
    private void start(AgentConversationQueue.Scope scope) {
        if (!UserUtils.isAdmin() && !UserUtils.hasPerm("agent:chat:instruct")) throw new IllegalStateException("排队权限已撤销");
        var session=sessions.requireActiveWrite(scope.sessionId());
        if (!Objects.equals(session.getUserId(),scope.userId()) || !Objects.equals(session.getTenantId(),scope.tenantId())) throw new IllegalStateException("会话归属已改变");
        var agent=agents.getById(session.getAgentId());
        if (agent==null || !Boolean.TRUE.equals(agent.getEnabled())) throw new IllegalStateException("智能体已禁用");
        visibility.assertUsable("AGENT",agent.getId());
        if (!Boolean.TRUE.equals(agent.getPublished()) && !com.gmd.faq.server.main.common.util.ResourceOwnershipSupport.isManageable(agent) && !UserUtils.isAdmin()) throw new IllegalStateException("智能体未发布");
        if (!agui.getPendingConfirms(Long.toString(scope.sessionId())).isEmpty()) { queue.pause(scope); return; }
        String runId=UUID.randomUUID().toString();
        var dispatch=queue.claim(scope,runId,coordinator.nodeId());
        if (dispatch==null) return;
        try {
            var item=dispatch.message();
            ChatMessageAppendDTO message=new ChatMessageAppendDTO(); message.setRole("user"); message.setContent(item.content());
            var saved=chats.appendMessage(scope.sessionId(),message);
            Map<String,Object> options=new HashMap<>(); options.put("agentId",agent.getAgentCode());
            options.put("memoryActive",item.options().memoryActive()); options.put("planActive",item.options().planActive());
            options.put("toolProcessActive",item.options().toolProcessActive()); options.put("fileIds",item.options().fileIds()); options.put("planMode",item.options().planMode());
            var input=RunAgentInput.builder().threadId(Long.toString(scope.sessionId())).runId(runId)
                    .messages(List.of(AguiMessage.userMessage(String.valueOf(saved.getId()),item.content())))
                    .forwardedProps(options).build();
            agui.handleQueued(input,agent.getAgentCode());
        } catch (RuntimeException error) { queue.finish(scope,runId,false,true); throw error; }
    }
}
