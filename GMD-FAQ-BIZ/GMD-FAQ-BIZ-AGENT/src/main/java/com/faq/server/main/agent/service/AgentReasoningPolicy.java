package com.gmd.faq.server.main.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.model.ModelResilienceSettings;
import com.gmd.faq.server.main.common.model.ReasoningConfigSupport;
import com.gmd.faq.server.main.common.model.ReasoningSettings;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import com.gmd.faq.server.main.model.service.ModelProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 保存和读取共用与运行时一致的推理规则，不把容错配置的管理员限制扩散到推理设置。 */
@Component
@RequiredArgsConstructor
public class AgentReasoningPolicy {
    private final ModelConfigService models;
    private final ModelProviderService providers;

    public ReasoningSettings resolve(AgentDefinitionVO agent) {
        if (agent.getAgentType() != AgentType.CUSTOM || agent.getModelConfigId() == null) return null;
        var config = models.getById(agent.getModelConfigId());
        var provider = config == null ? null : providers.getById(config.getProviderId());
        if (provider == null) return agent.getModelParamsOverride() != null && agent.getModelParamsOverride().hasNonNull("reasoning")
                ? ReasoningConfigSupport.parse(agent.getModelParamsOverride().get("reasoning")) : ReasoningSettings.defaults();
        agent.setModelParamsOverride(ReasoningConfigSupport.normalizeOverride(config, provider, agent.getModelParamsOverride()));
        return ReasoningConfigSupport.effective(config, provider, agent.getModelParamsOverride());
    }

    public void validate(AgentDefinitionVO agent) {
        JsonNode overrides = agent.getModelParamsOverride();
        if (agent.getAgentType() != AgentType.CUSTOM || overrides == null) return;
        var primary = models.getModelWrapperById(agent.getModelConfigId());
        overrides = ReasoningConfigSupport.normalizeOverride(primary.getConfig(), primary.getProvider(), overrides);
        agent.setModelParamsOverride(overrides);
        var settings = ReasoningConfigSupport.effective(primary.getConfig(), primary.getProvider(), overrides);
        JsonNode structured = overrides.hasNonNull("reasoning") ? overrides.get("reasoning")
                : primary.getConfig().getExtendConfig() == null ? null : primary.getConfig().getExtendConfig().get("reasoning");
        ReasoningConfigSupport.rejectConflicts(overrides.get("extendConfig"), structured);
        Integer maxTokens = overrides.hasNonNull("maxTokens") ? overrides.get("maxTokens").asInt() : primary.getConfig().getMaxTokens();
        if (structured != null) ReasoningConfigSupport.validate(settings, ReasoningConfigSupport.capabilities(primary.getConfig(), primary.getProvider()), maxTokens);
        if (overrides.hasNonNull("reasoning") && agent.getModelResilienceConfig() != null) {
            Long backupId = ModelResilienceSettings.parse(agent.getModelResilienceConfig()).fallbackModelId();
            if (backupId != null) {
                var backup = models.getModelWrapperById(backupId);
                ReasoningConfigSupport.validate(settings, ReasoningConfigSupport.capabilities(backup.getConfig(), backup.getProvider()), backup.getConfig().getMaxTokens());
            }
        }
    }
}
