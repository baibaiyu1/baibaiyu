package com.gmd.faq.server.main.engine.model.impl;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.model.IChatModel;
import com.gmd.faq.server.main.engine.model.GenerateOptionsHelper;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicMultiAgentFormatter;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

/**
 * 描述：Anthropic 模型
 *
 **/
@Component
public class DefaultAnthropicModelI implements IChatModel {
    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (config.isMulti()) {
            builder.formatter(new AnthropicMultiAgentFormatter());
        } else {
            builder.formatter(new AnthropicChatFormatter());
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(false)
                .defaultOptions(GenerateOptionsHelper.create(config));

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.ANTHROPIC;
    }

    @Override
    public int order() {
        return 0;
    }
}
