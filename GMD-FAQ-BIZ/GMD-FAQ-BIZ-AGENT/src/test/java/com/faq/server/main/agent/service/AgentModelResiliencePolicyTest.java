package com.gmd.faq.server.main.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.*;
import com.gmd.faq.server.main.common.model.ModelResilienceSettings;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelResiliencePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final AgentModelResiliencePolicy policy = new AgentModelResiliencePolicy(models);
    private ModelWrapper wrapper(long tenant,ModelProviderType provider,String types,int context) throws Exception {
        ModelConfig model = new ModelConfig(); model.setTenantId(tenant);model.setModelType(json.readTree(types));model.setContextWindow(context);
        ModelProvider source = new ModelProvider();source.setTenantId(tenant);source.setType(provider);
        return ModelWrapper.builder().config(model).provider(source).build();
    }
    @AfterEach void cleanup() { TenantUtils.clear(); }
    @Test void rejectsInvalidBudgetCrossTenantMissingModalitiesAndSmallerContext() throws Exception {
        TenantUtils.setCurrentTenant(1L,"t");
        when(models.getModelWrapperById(1L)).thenReturn(wrapper(1,ModelProviderType.OPEN_AI,"[\"CHAT\",\"IMAGE\"]",32000));
        var config = json.readTree("{\"maxAttempts\":2,\"fallbackModelId\":\"2\"}");
        when(models.getModelWrapperById(2L)).thenReturn(wrapper(2,ModelProviderType.OPEN_AI,"[\"CHAT\",\"IMAGE\"]",32000));
        assertThrows(IllegalArgumentException.class,() -> policy.validate(AgentType.CUSTOM,1L,config));
        when(models.getModelWrapperById(2L)).thenReturn(wrapper(1,ModelProviderType.OPEN_AI,"[\"CHAT\"]",32000));
        assertThrows(IllegalArgumentException.class,() -> policy.validate(AgentType.CUSTOM,1L,config));
        when(models.getModelWrapperById(2L)).thenReturn(wrapper(1,ModelProviderType.OPEN_AI,"[\"CHAT\",\"IMAGE\"]",16000));
        assertThrows(IllegalArgumentException.class,() -> policy.validate(AgentType.CUSTOM,1L,config));
        when(models.getModelWrapperById(2L)).thenReturn(wrapper(1,ModelProviderType.OPEN_AI,"[\"CHAT\",\"IMAGE\"]",32000));
        assertDoesNotThrow(() -> policy.validate(AgentType.CUSTOM,1L,config));
        assertThrows(IllegalArgumentException.class,() -> policy.validate(AgentType.CUSTOM,1L,config,json.readTree("{\"contextWindow\":64000}")));
        for (String invalid : new String[]{"{\"maxAttempts\":0}","{\"maxAttempts\":6}","{\"maxAttempts\":4294967297}","{\"maxAttempts\":1.2}","{\"fallbackModelId\":\"9223372036854775808\"}"}) {
            assertThrows(IllegalArgumentException.class,() -> ModelResilienceSettings.parse(json.readTree(invalid)));
        }
    }
    @Test void summaryModelMayUseDifferentProviderButMustFitHistory() throws Exception {
        TenantUtils.setCurrentTenant(1L,"t");
        when(models.getModelWrapperById(1L)).thenReturn(wrapper(1,ModelProviderType.OPEN_AI,"[\"CHAT\"]",32000));
        when(models.getModelWrapperById(2L)).thenReturn(wrapper(1,ModelProviderType.ANTHROPIC,"[\"CHAT\"]",64000));
        assertDoesNotThrow(() -> policy.validate(AgentType.CUSTOM,1L,json.readTree("{\"summaryModelId\":\"2\"}")));
        assertThrows(IllegalArgumentException.class,() -> policy.validate(AgentType.CUSTOM,1L,json.readTree("{\"fallbackModelId\":\"2\"}")));
    }
}
