package com.gmd.faq.server.main.engine.model.impl;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.model.IChatModel;
import com.gmd.faq.server.main.engine.model.GenerateOptionsHelper;
import com.gmd.faq.server.main.engine.model.ChatModelTransportConfig;
import com.gmd.faq.server.main.engine.model.HttpTransportHelper;
import io.agentscope.extensions.model.ollama.formatter.OllamaChatFormatter;
import io.agentscope.extensions.model.ollama.formatter.OllamaMultiAgentFormatter;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import org.springframework.stereotype.Component;

/**
 * 描述：Ollama 模型
 *
 **/
@Component
public class DefaultOllamaModelI implements IChatModel {
    private final ChatModelTransportConfig transportConfig;

    public DefaultOllamaModelI(ChatModelTransportConfig transportConfig) {
        this.transportConfig = transportConfig;
    }

    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            throw new IllegalArgumentException("Base URL is null");
        }

        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelCode())
                .httpTransport(transportConfig.createChatTransport())
                .defaultOptions(OllamaOptions.fromGenerateOptions(GenerateOptionsHelper.create(config)));

        if (config.isMulti()) {
            builder.formatter(new OllamaMultiAgentFormatter());
        } else {
            builder.formatter(new OllamaChatFormatter());
        }

        return builder.build();
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            throw new IllegalArgumentException("Base URL is null");
        }

        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelCode())
                .httpTransport(HttpTransportHelper.createOkHttpTransport(10, 15))
                .defaultOptions(OllamaOptions.fromGenerateOptions(GenerateOptionsHelper.create(config)));

        return builder.build();
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.OLLAMA;
    }

    @Override
    public int order() {
        return 0;
    }
}
