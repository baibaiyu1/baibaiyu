package com.gmd.faq.server.main.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelResilienceAccessTest {
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    @Test void resourceOwnerCannotCreateChangeOrClearAdminModelPolicy() {
        var request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL,UserDetail.builder().id(100L).tenantId(1L).authType("ACCOUNT").perms(List.of()).build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var service = mock(AgentDefinitionServiceImpl.class, CALLS_REAL_METHODS);
        var config = new ObjectMapper().createObjectNode().put("maxAttempts",2);
        AgentDefinitionVO submitted = new AgentDefinitionVO();submitted.setAgentCode("owner");submitted.setModelResilienceConfig(config);
        assertThrows(IllegalStateException.class,() -> service.saveAgentDefinition(submitted));
        AgentDefinition existing = new AgentDefinition();existing.setId(7L);existing.setOwnerId(100L);existing.setVisibility("PRIVATE");existing.setAgentCode("owner");
        doReturn(existing).when(service).getById(7L);doReturn(new AgentDefinitionVO()).when(service).agentDefinitionDetail(7L);
        submitted.setId(7L);
        assertThrows(IllegalStateException.class,() -> service.updateAgentDefinition(submitted));
        existing.setModelResilienceConfig(config);submitted.setModelResilienceConfig(null);
        assertThrows(IllegalStateException.class,() -> service.updateAgentDefinition(submitted));
    }
}
