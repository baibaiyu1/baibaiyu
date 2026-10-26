package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.*;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.agentscope.spring.boot.agui.mvc.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentInstructionControllerTest {
    private final SessionAccessGuard guard = mock(SessionAccessGuard.class);
    private final AgentDefinitionService agents = mock(AgentDefinitionService.class);
    private final ResourceVisibilityService visibility = mock(ResourceVisibilityService.class);
    private final AgentInstructionService inbox = mock(AgentInstructionService.class);
    private final AguiMvcController agui = mock(AguiMvcController.class);
    private final RunTracker tracker = mock(RunTracker.class);
    private final AgentInstructionController controller = new AgentInstructionController(guard,agents,visibility,inbox,agui);

    @BeforeEach void setup() {
        principal(2,1,UserUtils.AUTH_ACCOUNT);
        ChatSession session = new ChatSession(); session.setId(3L); session.setTenantId(1L); session.setUserId(2L); session.setAgentId(4L);
        when(guard.requireWrite(3L)).thenReturn(session); when(guard.requireActiveWrite(3L)).thenReturn(session);
        when(agui.getRunTracker()).thenReturn(tracker);
        when(tracker.snapshot("3")).thenReturn(new RunTracker.RunSnapshot("active",RunTracker.RunState.RUNNING,Instant.now()));
        AgentDefinition agent = new AgentDefinition(); agent.setId(4L); agent.setEnabled(true); agent.setPublished(true);
        when(agents.getById(4L)).thenReturn(agent);
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void refusesGuestsPreviewOtherOwnersAndOtherTenants() {
        principal(2,1,UserUtils.AUTH_CHAT_KEY); assertThrows(IllegalStateException.class, () -> controller.list("3"));
        principal(2,1,UserUtils.AUTH_ACCOUNT); assertThrows(IllegalArgumentException.class, () -> controller.list("preview"));
        principal(5,1,UserUtils.AUTH_ACCOUNT); assertThrows(IllegalStateException.class, () -> controller.list("3"));
        principal(2,9,UserUtils.AUTH_ACCOUNT); assertThrows(IllegalStateException.class, () -> controller.list("3"));
        verifyNoInteractions(inbox);
    }
    @Test void refusesOldRunsStoppingAndDisabledAgentsWithoutEnqueuing() {
        assertEquals(400,controller.submit("3",new AgentInstructionController.Submit("old","request","补充")).getCode());
        when(tracker.snapshot("3")).thenReturn(new RunTracker.RunSnapshot("active",RunTracker.RunState.STOPPING,Instant.now()));
        assertEquals(400,controller.submit("3",new AgentInstructionController.Submit("active","request","补充")).getCode());
        when(agents.getById(4L)).thenReturn(null);
        assertEquals(400,controller.submit("3",new AgentInstructionController.Submit("active","request","补充")).getCode());
        verifyNoInteractions(inbox);
    }
    @Test void mvcBindsThreadIdWithoutCompilerParameterMetadata() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        var user = UserDetail.builder().id(2L).tenantId(1L).authType(UserUtils.AUTH_ACCOUNT).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/agui/instructions/3")
                .requestAttr(SysConst.USER_DETAIL,user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
    }
    private void principal(long id,long tenant,String authType) {
        var request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL,UserDetail.builder().id(id).tenantId(tenant).authType(authType).build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
