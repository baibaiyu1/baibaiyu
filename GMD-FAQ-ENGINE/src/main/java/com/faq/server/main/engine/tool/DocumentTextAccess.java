package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.common.entity.Attach;
import com.gmd.faq.server.main.common.util.AgentStateScope;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 附件归属和会话授权独立于模型参数、文件名以及记忆开关。 */
@Component
@RequiredArgsConstructor
public class DocumentTextAccess {
    private static final String STATE_KEY = "gmd_attachment_ids";
    private final AttachService attachService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentStateStore stateStore = new io.agentscope.core.state.InMemoryAgentStateStore();

    public Attach requireOwned(Long fileId) {
        return requireOwned(fileId, TenantUtils.getCurrentTenantId(), UserUtils.getId());
    }

    private Attach requireOwned(Long fileId, Long tenantId, Long accountId) {
        Attach attach = fileId == null ? null : attachService.getById(fileId);
        if (tenantId == null || accountId == null || accountId == 0L || attach == null
                || !Objects.equals(tenantId, attach.getTenantId()) || !Objects.equals(accountId, attach.getCreateBy())) {
            throw new IllegalStateException("附件不存在或无权读取");
        }
        return attach;
    }

    public void authorizeForSession(AgentContext owner) {
        String userId = userId(owner);
        List<String> submitted = owner.getFileIds() == null ? List.of() : owner.getFileIds();
        // 全部验证通过后再落授权，不能只信任客户端提交的 ID。
        submitted.forEach(id -> requireOwned(parseId(id), owner.getTenantId(), owner.getUserInfo().getId()));
        var ids = new LinkedHashSet<>(stateStore.get(userId, sessionKey(owner), STATE_KEY, AttachmentIds.class)
                .map(AttachmentIds::ids).orElse(List.of()));
        ids.addAll(submitted);
        if (!submitted.isEmpty()) stateStore.save(userId, sessionKey(owner), STATE_KEY, new AttachmentIds(List.copyOf(ids)));
    }

    public void requireForSession(Long fileId, AgentContext owner) {
        String userId = userId(owner);
        requireOwned(fileId, owner.getTenantId(), owner.getUserInfo().getId());
        boolean attached = stateStore.get(userId, sessionKey(owner), STATE_KEY, AttachmentIds.class)
                .map(value -> value.ids().contains(String.valueOf(fileId))).orElse(false);
        if (!attached) throw new IllegalStateException("附件未提交到当前会话");
    }

    private String sessionKey(AgentContext owner) { return owner.getThreadId() + ":attachments"; }

    private String userId(AgentContext owner) {
        if (owner == null || owner.getUserInfo() == null || owner.getThreadId() == null || owner.getThreadId().isBlank())
            throw new IllegalStateException("附件读取缺少服务端会话身份");
        return AgentStateScope.userId(owner.getTenantId(), owner.getUserInfo().getId());
    }

    static Long parseId(String id) {
        if (id == null || !id.matches("[0-9]{1,19}")) throw new IllegalArgumentException("无效的附件 ID");
        try { return Long.valueOf(id); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("无效的附件 ID"); }
    }

    public record AttachmentIds(List<String> ids) implements State {}
}
