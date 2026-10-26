package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.agent.mapper.ChatSessionMapper;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.ChatSession;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionAccessGuardTest {

    private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionAccessGuard guard = new SessionAccessGuard(mapper, jdbc);
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TenantUtils.setCurrentTenant(1L, "test");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        TenantUtils.clear();
    }

    @Test
    void ownerAndAdminCanWriteButOtherMemberCannotRead() {
        ChatSession session = session(10L, 100L, 20L);
        principal(10L, UserUtils.AUTH_ACCOUNT, null, List.of());
        assertTrue(guard.canRead(session));
        assertTrue(guard.canWrite(session));

        principal(11L, UserUtils.AUTH_ACCOUNT, null, List.of());
        assertFalse(guard.canRead(session));
        assertFalse(guard.canWrite(session));

        principal(11L, UserUtils.AUTH_ACCOUNT, null, List.of(UserUtils.PERM_ADMIN));
        assertTrue(guard.canRead(session));
        assertTrue(guard.canWrite(session));
    }

    @Test
    void leaderCanReadDescendantSessionButCannotWrite() {
        ChatSession session = session(10L, 100L, 30L);
        principal(11L, UserUtils.AUTH_ACCOUNT, null, List.of());
        when(jdbc.queryForList(contains("department_id"), eq(Long.class), eq(11L), eq(1L)))
                .thenReturn(List.of(20L));
        when(jdbc.queryForList(contains("parent_id"), eq(1L)))
                .thenReturn(List.of(Map.of("id", 20L, "parent_id", 0L),
                        Map.of("id", 30L, "parent_id", 20L)));

        assertTrue(guard.canRead(session));
        assertFalse(guard.canWrite(session));
    }

    @Test
    void chatKeyIsLimitedToItsOwnerAndBoundAgent() {
        principal(10L, UserUtils.AUTH_CHAT_KEY, 100L, List.of());
        assertTrue(guard.canWrite(session(10L, 100L, null)));
        assertFalse(guard.canRead(session(10L, 101L, null)));
        assertFalse(guard.canRead(session(11L, 100L, null)));
    }

    private void principal(Long id, String authType, Long boundAgentId, List<String> perms) {
        request.setAttribute(SysConst.USER_DETAIL, UserDetail.builder().id(id)
                .tenantId(1L).tenantCode("test").authType(authType)
                .boundAgentId(boundAgentId).perms(perms).build());
    }

    private ChatSession session(Long ownerId, Long agentId, Long departmentId) {
        ChatSession session = new ChatSession();
        session.setId(99L);
        session.setUserId(ownerId);
        session.setAgentId(agentId);
        session.setOwnerDeptId(departmentId);
        return session;
    }
}
