package com.gmd.faq.server.main.engine.model;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.model.ReasoningConfigSupport;
import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.util.ExtendConfigHelper;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.engine.formatter.ToolResultImageModel;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 描述： 聊天模型工厂
 *
 **/
@Component
public class ChatModelFactory {
    private static final Map<ModelProviderType, IChatModel> MODEL_MAP = new ConcurrentHashMap<>();

    private final ModelConfigService modelConfigService;
    private final com.gmd.faq.server.main.engine.telemetry.AgentTelemetry telemetry;

    public ChatModelFactory(List<IChatModel> IChatModels, ModelConfigService modelConfigService,
            com.gmd.faq.server.main.engine.telemetry.AgentTelemetry telemetry) {
        this.modelConfigService = modelConfigService;
        this.telemetry = telemetry;
        IChatModels.stream()
                .collect(Collectors.groupingBy(IChatModel::getProvider))
                .forEach((provider, models) -> {
                    // 降序
                    models.sort((o1, o2) -> o2.order() - o1.order());
                    // 获取优先级最高的实现
                    MODEL_MAP.put(provider, models.getFirst());
                });
    }

    /**
     * 获取模型
     *
     * @param agentDefinition ModelConfigWrapper
     * @return 模型
     */
    public Model getModel(AgentDefinition agentDefinition) {
        return getModel(agentDefinition, false);
    }

    /**
     * 获取模型
     *
     * @param agentDefinition ModelConfigWrapper
     * @return 模型
     */
    public Model getModel(AgentDefinition agentDefinition, boolean multi) {
        return getModel(agentDefinition, agentDefinition.getModelConfigId(), multi, true, "MODEL");
    }

    public Model getAuxiliaryModel(AgentDefinition definition, Long modelId, String operation) {
        return getModel(definition, modelId, false, false, operation);
    }

    public com.gmd.faq.server.main.common.model.ReasoningSettings getReasoningSettings(AgentDefinition definition) {
        var model = modelConfigService.getModelWrapperById(definition.getModelConfigId());
        return com.gmd.faq.server.main.common.model.ReasoningConfigSupport.effective(model.getConfig(), model.getProvider(), definition.getModelParamsOverride());
    }

    private Model getModel(AgentDefinition agentDefinition, Long modelId, boolean multi, boolean overrides, String operation) {
        ModelConfigWrapper configWrapper = ModelConfigWrapper.builder().build();
        ModelWrapper config = modelConfigService.getModelWrapperById(modelId);
        JsonNode agentOverrides = agentDefinition.getModelParamsOverride();
        if (!"COMPACTION".equals(operation)) {
            ModelWrapper primary = java.util.Objects.equals(modelId, agentDefinition.getModelConfigId()) ? config
                    : modelConfigService.getModelWrapperById(agentDefinition.getModelConfigId());
            agentOverrides = ReasoningConfigSupport.normalizeOverride(primary.getConfig(), primary.getProvider(), agentOverrides);
        }
        JsonNode reasoningOverride = "COMPACTION".equals(operation) || agentOverrides == null ? null : agentOverrides.get("reasoning");
        // 推理意图同样约束备用模型；摘要和子智能体使用自己的模型配置。
        if (!"COMPACTION".equals(operation) && reasoningOverride != null && !reasoningOverride.isNull())
            configWrapper.setReasoning(reasoningOverride);

        // 获取agent中配置的参数
        JsonNode modelParamsOverride = overrides ? agentOverrides : null;
        if (modelParamsOverride != null && !modelParamsOverride.isEmpty()) {
            if (modelParamsOverride.has("temperature")) {
                configWrapper.setTemperature(modelParamsOverride.get("temperature").asDouble());
            }
            if (modelParamsOverride.has("topP")) {
                configWrapper.setTopP(modelParamsOverride.get("topP").asDouble());
            }
            if (modelParamsOverride.has("topK")) {
                configWrapper.setTopK(modelParamsOverride.get("topK").asInt());
            }
            if (modelParamsOverride.has("maxTokens")) {
                configWrapper.setMaxTokens(modelParamsOverride.get("maxTokens").asInt());
            }
            if (modelParamsOverride.has("contextWindow")) {
                configWrapper.setContextWindow(modelParamsOverride.get("contextWindow").asInt());
            }
            if (modelParamsOverride.has("repeatPenalty")) {
                configWrapper.setRepeatPenalty(modelParamsOverride.get("repeatPenalty").asDouble());
            }
            if (modelParamsOverride.has("streaming")) {
                configWrapper.setStreaming(modelParamsOverride.get("streaming").asBoolean());
            }
            if (modelParamsOverride.has("seed")) {
                configWrapper.setSeed(modelParamsOverride.get("seed").asLong());
            }
            // 解析 extendConfig 填充 headers、queryParams、bodyParams（agent 级覆盖优先）
            JsonNode extendConfig = modelParamsOverride.get("extendConfig");
            if (extendConfig != null && !extendConfig.isNull() && extendConfig.isObject()) {
                ExtendConfigHelper.fillOverride(configWrapper, extendConfig);
            }
        }

        // 填充模型配置
        config.getConfig().fillModelConfigWrapper(configWrapper);
        // 填充供应商配置
        config.getProvider().fillModelConfigWrapper(configWrapper);
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.rejectConflicts(config.getConfig().getExtendConfig(),
                config.getConfig().getExtendConfig() == null ? null : config.getConfig().getExtendConfig().get("reasoning"));
        if (!"COMPACTION".equals(operation) && agentOverrides != null && configWrapper.getReasoning() != null)
            com.gmd.faq.server.main.common.model.ReasoningConfigSupport.rejectConflicts(
                    agentOverrides.get("extendConfig"), configWrapper.getReasoning());
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(configWrapper);
        if (configWrapper.getApiType() == com.gmd.faq.server.main.common.enums.ModelApiType.RESPONSES
                && configWrapper.getProvider() != ModelProviderType.OPEN_AI)
            throw new IllegalArgumentException("Responses 协议仅支持 OpenAI 类型供应商");
        configWrapper.setMulti(multi);
        boolean noTools = "COMPACTION".equals(operation) || "VOICE".equals(operation);
        configWrapper.setToolChoiceStrategy(noTools
                ? com.gmd.faq.server.main.common.enums.ToolChoiceStrategy.NONE : agentDefinition.getToolChoiceStrategy());
        configWrapper.setSpecificToolName(noTools ? null : agentDefinition.getSpecificToolName());

        IChatModel IChatModel = MODEL_MAP.get(configWrapper.getProvider());
        if (IChatModel == null) {
            throw new RuntimeException("No chat model found for provider " + configWrapper.getProvider());
        }

        return telemetry.observe(new ToolResultImageModel(IChatModel.getModel(configWrapper)),agentDefinition.getId(),operation);
    }

    /**
     * 获取简单的模型
     *
     * @param configWrapper 模型配置
     * @return 模型
     */
    public Model getSimpleModel(ModelConfigWrapper configWrapper) {
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(configWrapper);
        if (configWrapper.getApiType() == com.gmd.faq.server.main.common.enums.ModelApiType.RESPONSES
                && configWrapper.getProvider() != ModelProviderType.OPEN_AI)
            throw new IllegalArgumentException("Responses 协议仅支持 OpenAI 类型供应商");
        IChatModel IChatModel = MODEL_MAP.get(configWrapper.getProvider());
        if (IChatModel == null) {
            throw new RuntimeException("No chat model found for provider " + configWrapper.getProvider());
        }

        return IChatModel.getSimpleModel(configWrapper);
    }
}
