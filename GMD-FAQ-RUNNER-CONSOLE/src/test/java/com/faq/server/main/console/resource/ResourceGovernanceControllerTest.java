package com.gmd.faq.server.main.console.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.common.service.ResourceSpaceService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceGovernanceControllerTest {
    @Test
    void ownershipEndpointBindsQueryAndBodyWithoutCompilerParameterMetadata() throws Exception {
        var service = mock(ResourceVisibilityService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ResourceGovernanceController(
                service, mock(AgentDefinitionService.class), mock(ResourceSpaceService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper())).build();
        mvc.perform(put("/resource/ownership")
                        .param("resourceType", "TOOL").param("resourceId", "42")
                        .contentType("application/json")
                        .content("{\"ownershipType\":\"DEPARTMENT\",\"departmentId\":\"10\",\"maintainerId\":\"7\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        verify(service).transferOwnership("TOOL", 42L, "DEPARTMENT", null, 10L, 7L);
    }
}
