package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.agent.mapper.IJobInfoMapper;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.AgentA2A;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDefinitionConfigClearTest {
    @Mock private AgentHookService hookService;
    @Mock private com.gmd.faq.server.main.agent.service.AgentReasoningPolicy reasoningPolicy;
    @Mock private AgentA2aService a2aService;
    @Mock private MessagePublisher messagePublisher;
    @Mock private IJobInfoMapper jobInfoMapper;
    @Spy @InjectMocks private AgentDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "config-clear-test"), AgentDefinition.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SysConst.USER_DETAIL, UserDetail.builder().id(100L).tenantId(1L)
                .authType("ACCOUNT").perms(List.of()).build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fullSaveExplicitlyClearsRemovedOptionalConfiguration() {
        AgentDefinition existing = configuredAgent();
        AgentDefinitionVO request = fullRequest();
        doReturn(existing).when(service).getById(7L);
        doReturn(request).when(service).agentDefinitionDetail(7L);
        doReturn(true).when(service).update(any(AgentDefinition.class), any(Wrapper.class));

        assertTrue(service.updateAgentDefinition(request));

        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(any(AgentDefinition.class), wrapper.capture());
        LambdaUpdateWrapper<AgentDefinition> clear = (LambdaUpdateWrapper<AgentDefinition>) wrapper.getValue();
        for (String column : List.of("model_params_override", "rag_config", "memory_compression_config",
                "structured_output_schema", "system_prompt_template_id")) {
            assertTrue(clear.getSqlSet().contains(column + "="), column + " must be cleared");
        }
        assertTrue(clear.getParamNameValuePairs().values().stream().allMatch(value -> value == null));
        assertTrue(clear.getSqlSegment().contains("id ="));
        assertTrue(clear.getParamNameValuePairs().containsValue(7L));
        verify(service, never()).updateById(any(AgentDefinition.class));
    }

    @Test
    void enabledOnlyUpdateDoesNotClearOptionalConfiguration() {
        doReturn(configuredAgent()).when(service).getById(7L);
        when(jobInfoMapper.selectList(any())).thenReturn(List.of());
        doReturn(true).when(service).updateById(any(AgentDefinition.class));
        AgentDefinitionVO request = new AgentDefinitionVO();
        request.setId(7L);
        request.setEnabled(false);

        assertTrue(service.updateAgentDefinition(request));

        ArgumentCaptor<AgentDefinition> updated = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service).updateById(updated.capture());
        assertEquals(false, updated.getValue().getEnabled());
        assertNull(updated.getValue().getModelParamsOverride());
        verify(service, never()).update(any(AgentDefinition.class), any(Wrapper.class));
    }

    @Test
    void fullSaveKeepsNonNullOptionalConfiguration() {
        AgentDefinition existing = configuredAgent();
        AgentDefinitionVO request = fullRequest();
        request.setModelParamsOverride(existing.getModelParamsOverride());
        request.setRagConfig(existing.getRagConfig());
        request.setMemoryCompressionConfig(existing.getMemoryCompressionConfig());
        request.setStructuredOutputSchema(existing.getStructuredOutputSchema());
        request.setSystemPromptTemplateId(existing.getSystemPromptTemplateId());
        doReturn(existing).when(service).getById(7L);
        doReturn(request).when(service).agentDefinitionDetail(7L);
        doReturn(true).when(service).updateById(any(AgentDefinition.class));

        assertTrue(service.updateAgentDefinition(request));

        ArgumentCaptor<AgentDefinition> updated = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service).updateById(updated.capture());
        assertEquals(existing.getModelParamsOverride(), updated.getValue().getModelParamsOverride());
        assertEquals(existing.getRagConfig(), updated.getValue().getRagConfig());
        assertEquals(9L, updated.getValue().getSystemPromptTemplateId());
        verify(service, never()).update(any(AgentDefinition.class), any(Wrapper.class));
    }

    private AgentDefinitionVO fullRequest() {
        AgentDefinitionVO request = new AgentDefinitionVO();
        request.setId(7L);
        request.setAgentType(AgentType.A2A);
        request.setAgentCode("config-test");
        request.setAgentA2A(new AgentA2A());
        request.setFollowTemplate(false);
        return request;
    }

    private AgentDefinition configuredAgent() {
        AgentDefinition existing = new AgentDefinition();
        existing.setId(7L);
        existing.setAgentCode("config-test");
        existing.setOwnerId(100L);
        existing.setVisibility("PRIVATE");
        existing.setSystemPromptTemplateId(9L);
        var config = new ObjectMapper().createObjectNode().put("value", 1);
        existing.setModelParamsOverride(config);
        existing.setRagConfig(config);
        existing.setMemoryCompressionConfig(config);
        existing.setStructuredOutputSchema(config);
        return existing;
    }
}
