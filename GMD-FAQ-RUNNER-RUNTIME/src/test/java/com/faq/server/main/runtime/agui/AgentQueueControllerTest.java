package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentQueueControllerTest {
    private final AgentConversationQueue queue=mock(AgentConversationQueue.class);
    private final SessionAccessGuard sessions=mock(SessionAccessGuard.class);
    private final AgentDefinitionService agents=mock(AgentDefinitionService.class);
    private final ResourceVisibilityService visibility=mock(ResourceVisibilityService.class);
    private final AgentRunCoordinator coordinator=mock(AgentRunCoordinator.class);
    private final AgentQueueController controller=new AgentQueueController(queue,sessions,agents,visibility,coordinator);

    @BeforeEach void setup() {
        principal(2,1,UserUtils.AUTH_ACCOUNT);
        ChatSession session=new ChatSession(); session.setId(3L); session.setTenantId(1L); session.setUserId(2L); session.setAgentId(4L);
        when(sessions.requireActiveWrite(3L)).thenReturn(session);
        AgentDefinition agent=new AgentDefinition(); agent.setId(4L); agent.setEnabled(true); agent.setPublished(true);
        when(agents.getById(4L)).thenReturn(agent); when(coordinator.nodeId()).thenReturn("node");
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void steeringRequiresOwnActiveSessionAndUsableAgent() {
        var request=new AgentQueueController.Steer("active");
        principal(2,1,UserUtils.AUTH_CHAT_KEY);
        assertThrows(IllegalStateException.class,() -> controller.steer(3,5,request));
        principal(8,1,UserUtils.AUTH_ACCOUNT);
        assertThrows(IllegalStateException.class,() -> controller.steer(3,5,request));
        principal(2,8,UserUtils.AUTH_ACCOUNT);
        assertThrows(IllegalStateException.class,() -> controller.steer(3,5,request));
        principal(2,1,UserUtils.AUTH_ACCOUNT); when(agents.getById(4L)).thenReturn(null);
        assertThrows(IllegalStateException.class,() -> controller.steer(3,5,request));
        verifyNoInteractions(queue);
    }
    @Test void steeringBindsTheTargetRunToTheServerNodeAndChecksResourceAccess() {
        controller.steer(3,5,new AgentQueueController.Steer("active"));
        verify(visibility).assertUsable("AGENT",4L);
        verify(queue).steer(new AgentConversationQueue.Scope(1,2,3),5,"active","node");
        doThrow(new IllegalStateException("无使用权")).when(visibility).assertUsable("AGENT",4L);
        assertThrows(IllegalStateException.class,() -> controller.steer(3,6,new AgentQueueController.Steer("active")));
        verifyNoMoreInteractions(queue);
    }
    private void principal(long id,long tenant,String authType) {
        var request=new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL,UserDetail.builder().id(id).tenantId(tenant).authType(authType).build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
