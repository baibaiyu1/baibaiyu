package com.gmd.faq.server.main.engine.plan;

import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.*;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 方案批准属于会话控制状态，不随记忆开关丢弃。原生 Plan Mode 状态由此处同步。 */
@Service
public class AgentPlanService {
    public static final String PATH = ".plans/PLAN.md";
    private static final String KEY = "gmd_plan_document";
    public static final int MAX_BYTES = 256_000;
    private final AgentStateStore store;
    private final Supplier<PlanWorkspace> workspace;
    private final SessionAccessGuard sessions;

    public record Document(String status, long revision, String digest, String content, String path,
                           String approvalCallId) implements State {
        public boolean readOnly() { return !"NONE".equals(status) && !"APPROVED".equals(status); }
    }
    private record Scope(String user, String session) {}

    @Autowired
    public AgentPlanService(ObjectProvider<AgentStateStore> stores, ObjectProvider<PlanWorkspace> workspaces,
                            SessionAccessGuard sessions) {
        this.store = stores.getIfAvailable(InMemoryAgentStateStore::new);
        this.workspace = workspaces::getObject;
        this.sessions = sessions;
    }
    public AgentPlanService(AgentStateStore store, PlanWorkspace workspace, SessionAccessGuard sessions) {
        this.store = store;
        this.workspace = () -> workspace;
        this.sessions = sessions;
    }

    public static boolean available(AgentContext owner) {
        if (owner == null || owner.getThreadId() == null || !owner.getThreadId().matches("[0-9]{1,19}")) return false;
        var user = owner.getPrincipal();
        return user != null && user.getId() != null && UserUtils.AUTH_ACCOUNT.equals(user.getAuthType());
    }

    public Document get(AgentContext owner) {
        Scope scope = scope(owner);
        return stored(scope);
    }

    public Document snapshot(AgentContext owner) {
        Document document = get(owner);
        if (("APPROVED".equals(document.status()) || "AWAITING_APPROVAL".equals(document.status()))
                && !matchesFile(owner, document)) {
            return new Document("STALE", document.revision(), document.digest(), document.content(), PATH, document.approvalCallId());
        }
        return document;
    }

    public boolean readOnly(AgentContext owner) { return snapshot(owner).readOnly(); }

    public Document enter(AgentContext owner) {
        assertPermission(owner);
        Document old = get(owner);
        if (old.readOnly()) return old;
        // 先撤销批准，再改文件；任一存储失败仍保持只读。
        Document entered = mutate(scope(owner), document -> new Document("PLANNING", document.revision() + 1,
                null, "", PATH, null));
        workspace.get().write(owner, PATH, "");
        return entered;
    }

    public void write(AgentContext owner, String path, String content) {
        assertPermission(owner);
        if (!PATH.equals(path)) throw new IllegalArgumentException("只能写入当前会话的方案文件");
        if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("方案不能为空且不能超过256KB");
        mutate(scope(owner), document -> new Document("PLANNING", document.revision() + 1,
                digest(content), content, PATH, null));
        workspace.get().write(owner, PATH, content);
    }

    public boolean requestApproval(AgentContext owner, String toolCallId) {
        assertPermission(owner);
        Document document = get(owner);
        if (!document.readOnly() || "REJECTED".equals(document.status()) || document.content().isBlank() || !matchesFile(owner, document)) return false;
        if ("AWAITING_APPROVAL".equals(document.status()) && Objects.equals(toolCallId, document.approvalCallId())) return true;
        mutate(scope(owner), old -> {
            if (old.revision() != document.revision()) throw new IllegalStateException("方案已变更，请重新提交");
            return new Document("AWAITING_APPROVAL", old.revision(), old.digest(), old.content(), PATH, toolCallId);
        });
        return true;
    }

    /** 仅在 Runtime 核对服务端待确认工具后调用；客户端不提交方案或版本。 */
    public void decide(AgentContext owner, String toolCallId, boolean approved) {
        assertPermission(owner);
        Document document = get(owner);
        // 决策已保存但进程在原生续跑前退出时，可凭仍待确认的同一调用继续；不重放已完成工具。
        if ("APPROVED".equals(document.status()) && approved && Objects.equals(toolCallId, document.approvalCallId())
                && matchesFile(owner, document)) return;
        if (!"AWAITING_APPROVAL".equals(document.status()) || !Objects.equals(toolCallId, document.approvalCallId()))
            throw new IllegalStateException("方案审批已失效，请重新提交方案");
        if (approved && !matchesFile(owner, document)) throw new IllegalStateException("方案文件已变更，旧批准无效，请先拒绝并重新提交");
        mutate(scope(owner), old -> {
            if (!old.equals(document)) throw new IllegalStateException("方案已变更，请重新审批");
            return new Document(approved ? "APPROVED" : "REJECTED", old.revision(), old.digest(), old.content(), PATH, toolCallId);
        });
    }

    public boolean approvedFor(AgentContext owner, String toolCallId) {
        Document document = snapshot(owner);
        return "APPROVED".equals(document.status()) && Objects.equals(toolCallId, document.approvalCallId());
    }

    public PlanModeManager manager() {
        var bridge = new WorkspaceManager(Path.of(".")) {
            @Override public void writeUtf8WorkspaceRelative(RuntimeContext ctx, String path, String content) {
                AgentPlanService.this.write(ctx.get(AgentContext.class), path, content);
            }
        };
        return new PlanModeManager(bridge, ".plans");
    }

    private Scope scope(AgentContext owner) {
        if (!available(owner)) throw new IllegalStateException("方案模式仅限有权限的登录账号正式会话");
        var session = sessions.requireWrite(Long.parseLong(owner.getThreadId()));
        var user = owner.getPrincipal();
        if (!Objects.equals(session.getTenantId(), owner.getTenantId()) || !Objects.equals(user.getTenantId(), owner.getTenantId()))
            throw new IllegalStateException("方案租户不匹配");
        return new Scope(AgentStateScope.userId(session.getTenantId(), session.getUserId()), owner.getThreadId());
    }

    private Document stored(Scope scope) {
        var document = store.getVersioned(scope.user(), scope.session(), KEY, Document.class).value();
        return document == null ? new Document("NONE", 0, null, "", PATH, null) : document;
    }

    private Document mutate(Scope scope, UnaryOperator<Document> update) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var versioned = store.getVersioned(scope.user(), scope.session(), KEY, Document.class);
            Document old = versioned.value();
            if (old == null) old = new Document("NONE", 0, null, "", PATH, null);
            Document next = update.apply(old);
            long version = store.saveIfVersion(scope.user(), scope.session(), KEY, next, versioned.version());
            if (!store.supportsVersioning() || version != AgentStateStore.UNVERSIONED) return next;
        }
        throw new IllegalStateException("方案正在被其他请求修改，请刷新后重试");
    }

    private boolean matchesFile(AgentContext owner, Document document) {
        if (document.digest() == null) return false;
        try { return document.digest().equals(digest(workspace.get().read(owner, PATH))); }
        catch (RuntimeException error) { return false; }
    }

    private static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void assertPermission(AgentContext owner) {
        if (!UserUtils.isAdmin() && !UserUtils.hasPerm("agent:chat:instruct"))
            throw new IllegalStateException("缺少方案操作权限");
        scope(owner);
        var session = sessions.requireActiveWrite(Long.parseLong(owner.getThreadId()));
        if (!Objects.equals(session.getUserId(), owner.getPrincipal().getId()))
            throw new IllegalStateException("只能操作本人的方案");
    }
}
