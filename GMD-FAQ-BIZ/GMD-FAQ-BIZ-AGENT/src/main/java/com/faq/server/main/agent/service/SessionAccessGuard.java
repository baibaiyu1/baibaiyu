package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.agent.mapper.ChatSessionMapper;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.util.ResourceDeptSupport;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.util.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 会话及其工作空间共用的访问边界。 */
@Component
@RequiredArgsConstructor
public class SessionAccessGuard {

    private static final String LEADER_DEPARTMENTS_ATTRIBUTE = SessionAccessGuard.class.getName() + ".departments";

    private final ChatSessionMapper chatSessionMapper;
    private final JdbcTemplate jdbcTemplate;

    public ChatSession requireRead(Long sessionId) {
        ChatSession session = load(sessionId);
        if (!canRead(session)) {
            throw new BusinessException("无权限查看该会话");
        }
        return session;
    }

    public ChatSession requireWrite(Long sessionId) {
        ChatSession session = load(sessionId);
        if (!canWrite(session)) {
            throw new BusinessException("无权限修改该会话");
        }
        return session;
    }

    public ChatSession requireActiveWrite(Long sessionId) {
        ChatSession session = requireWrite(sessionId);
        if (session.getMessageTable() != null && !session.getMessageTable().isBlank()) {
            throw new BusinessException("当前会话已经归档，不支持修改文件或继续对话");
        }
        return session;
    }

    public boolean canRead(ChatSession session) {
        if (session == null || UserUtils.getUserDetail() == null) {
            return false;
        }
        if (canWrite(session)) {
            return true;
        }
        if (UserUtils.isChatKey() || UserUtils.isSk() || session.getOwnerDeptId() == null) {
            return false;
        }
        return leaderDepartmentIds().contains(session.getOwnerDeptId());
    }

    public boolean canWrite(ChatSession session) {
        if (session == null || UserUtils.getUserDetail() == null) {
            return false;
        }
        if (!UserUtils.isChatKey() && !UserUtils.isSk() && UserUtils.isAdmin()) {
            return true;
        }
        Long currentUserId = UserUtils.getId();
        if (currentUserId == null || currentUserId == 0L
                || !Objects.equals(session.getUserId(), currentUserId)) {
            return false;
        }
        return !UserUtils.isChatKey() || Objects.equals(session.getAgentId(), UserUtils.getBoundAgentId());
    }

    public Set<Long> leaderDepartmentIds() {
        HttpServletRequest request = WebUtils.getRequest();
        if (request != null && request.getAttribute(LEADER_DEPARTMENTS_ATTRIBUTE) instanceof Set<?> cached) {
            @SuppressWarnings("unchecked")
            Set<Long> ids = (Set<Long>) cached;
            return ids;
        }
        Long accountId = UserUtils.getId();
        Long tenantId = TenantUtils.getCurrentTenantId();
        if (accountId == null || accountId == 0L || tenantId == null
                || UserUtils.isChatKey() || UserUtils.isSk()) {
            return Set.of();
        }
        List<Long> roots = jdbcTemplate.queryForList(
                "SELECT department_id FROM " + TableConst.DEPARTMENT_LEADER
                        + " WHERE account_id = ? AND tenant_id = ?", Long.class, accountId, tenantId);
        if (roots.isEmpty()) {
            return Set.of();
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT id, parent_id FROM " + TableConst.DEPARTMENT + " WHERE tenant_id = ?", tenantId);
        var parents = ResourceDeptSupport.parentMap(rows);
        Set<Long> allowed = new HashSet<>();
        for (Long root : roots) {
            if (parents.containsKey(root)) {
                allowed.addAll(ResourceDeptSupport.collectSubtree(parents, root));
            }
        }
        if (request != null) {
            request.setAttribute(LEADER_DEPARTMENTS_ATTRIBUTE, Set.copyOf(allowed));
        }
        return allowed;
    }

    private ChatSession load(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException("会话ID不能为空");
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在或已删除");
        }
        return session;
    }
}
