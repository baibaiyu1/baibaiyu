package com.gmd.faq.server.main.engine.model.impl;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.model.IChatModel;
import com.gmd.faq.server.main.engine.model.GenerateOptionsHelper;
import com.gmd.faq.server.main.engine.model.ChatModelTransportConfig;
import com.gmd.faq.server.main.engine.model.HttpTransportHelper;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeMultiAgentFormatter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

/**
 * 描述：DashScope 模型
 *
 **/
@Component
public class DefaultDashScopeModelI implements IChatModel {
    private final ChatModelTransportConfig transportConfig;

    public DefaultDashScopeModelI(ChatModelTransportConfig transportConfig) {
        this.transportConfig = transportConfig;
    }

    @Override
    public Model getModel(ModelConfigWrapper config) {
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(config);
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .enableThinking(config.getThinking())
                .httpTransport(transportConfig.createChatTransport())
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (config.isMulti()) {
            builder.formatter(new DashScopeMultiAgentFormatter());
        } else {
            builder.formatter(new DashScopeChatFormatter());
        }
        if (isMultimodalModel(config.getModelCode())) {
            builder.endpointType(EndpointType.MULTIMODAL);
            builder.formatter(new DashScopeChatFormatter());
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.apply(config);
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(false)
                .enableThinking(config.getThinking())
                .httpTransport(HttpTransportHelper.createOkHttpTransport(10, 15))
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }
        if (isMultimodalModel(config.getModelCode())) {
            builder.endpointType(EndpointType.MULTIMODAL);
            builder.formatter(new DashScopeChatFormatter());
        }

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.DASH_SCOPE;
    }

    @Override
    public int order() {
        return 0;
    }
    public static boolean isMultimodalModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lowerModelName = modelName.toLowerCase();
        return lowerModelName.startsWith("qvq")
                || lowerModelName.contains("-vl")
                || lowerModelName.contains("-asr")
                || lowerModelName.startsWith("qwen3.5")
                || lowerModelName.startsWith("qwen3.6")
                || lowerModelName.startsWith("qwen3.7")
                || lowerModelName.contains("kimi-k2.5")
                || lowerModelName.contains("kimi-k2.6");
    }
}
