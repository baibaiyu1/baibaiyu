package com.gmd.faq.server.main.engine.model.impl;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.engine.model.ResponsesModelOptions;
import com.gmd.faq.server.main.engine.model.ThinkingToolChoiceModel;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.formatter.FixedSysMsgOpenAIChatFormatter;
import com.gmd.faq.server.main.engine.formatter.FixedSysMsgOpenAIMultiAgentFormatter;
import com.gmd.faq.server.main.engine.model.IChatModel;
import com.gmd.faq.server.main.engine.model.GenerateOptionsHelper;
import com.gmd.faq.server.main.engine.model.ChatModelTransportConfig;
import com.gmd.faq.server.main.engine.model.HttpTransportHelper;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import io.agentscope.extensions.model.openaiofficial.ResponsesMultiAgentFormatter;
import org.springframework.stereotype.Component;

/**
 * 描述：OpenAI 模型
 *
 **/
@Component
public class DefaultOpenAIModelI implements IChatModel {
    private final ChatModelTransportConfig transportConfig;

    public DefaultOpenAIModelI(ChatModelTransportConfig transportConfig) {
        this.transportConfig = transportConfig;
    }

    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }
        if (config.getApiType() == ModelApiType.RESPONSES) return responses(config, false);

        var options = GenerateOptionsHelper.create(config);
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .httpTransport(transportConfig.createChatTransport())
                .generateOptions(options);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (config.isMulti()) {
            if (config.getFixedSystemMessage() != null && config.getFixedSystemMessage()) {
                builder.formatter(new FixedSysMsgOpenAIMultiAgentFormatter());
            } else {
                builder.formatter(new OpenAIMultiAgentFormatter());
            }

        } else {
            if (config.getFixedSystemMessage() != null && config.getFixedSystemMessage()) {
                builder.formatter(new FixedSysMsgOpenAIChatFormatter());
            } else {
                builder.formatter(new OpenAIChatFormatter());
            }
        }

        return ThinkingToolChoiceModel.wrap(builder.build(), config, options);
    }

    @Override
    public Model getSimpleModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }
        if (config.getApiType() == ModelApiType.RESPONSES) return responses(config, true);

        var options = GenerateOptionsHelper.create(config);
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .httpTransport(HttpTransportHelper.createOkHttpTransport(10, 15))
                .generateOptions(options);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            builder.baseUrl(config.getBaseUrl());
        }

        return ThinkingToolChoiceModel.wrap(builder.build(), config, options);
    }

    private Model responses(ModelConfigWrapper config, boolean probe) {
        var options = ResponsesModelOptions.create(config, probe ? 15 : transportConfig.getChatReadTimeoutSeconds(), probe);
        var builder = OpenAIResponsesChatModel.builder().apiKey(config.getApiKey()).modelName(config.getModelCode())
                .stream(!probe && Boolean.TRUE.equals(config.getStreaming()))
                .generateOptions(options)
                .includeEncryptedReasoning(!"DEEPSEEK".equals(com.gmd.faq.server.main.common.model.ReasoningConfigSupport.capabilities(
                        config.getProvider(), config.getBaseUrl(), config.getModelCode(), config.getApiType(), config.getReasoningProfile()).profile()))
                .additionalHeaders(config.getHeaders()).strictTools(false);
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) builder.baseUrl(config.getBaseUrl());
        if (config.getContextWindow() != null && config.getContextWindow() > 0) builder.contextWindowSize(config.getContextWindow());
        if (config.isMulti()) builder.formatter(new ResponsesMultiAgentFormatter());
        return ThinkingToolChoiceModel.wrap(builder.build(), config, options);
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.OPEN_AI;
    }

    @Override
    public int order() {
        return 0;
    }
}
