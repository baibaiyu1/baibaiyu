package com.gmd.faq.server.main.engine.tool;

import com.gmd.faq.server.main.common.entity.Attach;
import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import com.gmd.faq.server.main.resource.service.AttachService;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentTextAccessTest {
    private final AttachService attachments = mock(AttachService.class);
    private final InMemoryAgentStateStore store = new InMemoryAgentStateStore();
    private final DocumentTextAccess access = access();
    private final AgentContext owner = owner(1L, 9L, "session-one");

    @BeforeEach void setup() {
        var file = new Attach(); file.setId(77L); file.setTenantId(1L); file.setCreateBy(9L);
        when(attachments.getById(77L)).thenReturn(file);
    }
    @AfterEach void cleanup() { AgentContext.clean(); }

    @Test void forgedIdsNeverGrantCrossAccountOrCrossTenantAccess() {
        for (AgentContext forged : List.of(owner(1L, 12L, "other"), owner(2L, 9L, "other"))) {
            forged.setFileIds(List.of("77"));
            assertThrows(IllegalStateException.class, () -> access.authorizeForSession(forged));
            assertThrows(IllegalStateException.class, () -> access.requireForSession(77L, forged));
        }
    }

    @Test void authorizationSurvivesAFreshInstanceAndIsRestrictedToTheSameSession() {
        assertThrows(IllegalStateException.class, () -> access.requireForSession(77L, owner));
        owner.setFileIds(List.of("77")); access.authorizeForSession(owner);
        owner.setFileIds(List.of());
        assertDoesNotThrow(() -> access().requireForSession(77L, owner));
        assertThrows(IllegalStateException.class, () -> access.requireForSession(77L, owner(1L, 9L, "session-two")));
        store.delete("tenant_1_user_9", "session-one:attachments");
        assertThrows(IllegalStateException.class, () -> access.requireForSession(77L, owner));
    }

    @Test void modelCannotSelectUnsubmittedFilesOrTraversePaths() {
        AgentContext.set(owner);
        var reader = new LoadFileTextContentTool(access);
        for (String name : List.of("77.baibaiyu", "../77.baibaiyu", "9223372036854775808.baibaiyu")) {
            assertEquals(io.agentscope.core.message.ToolResultState.ERROR, reader.loadFileTextContent(name, null).block().getState());
        }
    }

    private DocumentTextAccess access() {
        var result = new DocumentTextAccess(attachments);
        ReflectionTestUtils.setField(result, "stateStore", store); return result;
    }
    private AgentContext owner(Long tenant, Long accountId, String session) {
        var user = new AccountVO(); user.setId(accountId);
        var result = new AgentContext(); result.setTenantId(tenant); result.setUserInfo(user); result.setThreadId(session);
        return result;
    }
}
