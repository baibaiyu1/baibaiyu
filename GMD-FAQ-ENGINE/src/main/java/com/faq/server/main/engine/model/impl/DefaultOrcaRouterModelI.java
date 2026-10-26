package com.gmd.faq.server.main.engine.model.impl;

import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.wrapper.ModelConfigWrapper;
import com.gmd.faq.server.main.engine.formatter.FixedSysMsgOpenAIChatFormatter;
import com.gmd.faq.server.main.engine.formatter.FixedSysMsgOpenAIMultiAgentFormatter;
import com.gmd.faq.server.main.engine.model.IChatModel;
import com.gmd.faq.server.main.engine.model.GenerateOptionsHelper;
import com.gmd.faq.server.main.engine.model.ChatModelTransportConfig;
import com.gmd.faq.server.main.engine.model.HttpTransportHelper;
import com.gmd.faq.server.main.engine.model.ThinkingToolChoiceModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;

/**
 * 描述：OrcaRouter 模型
 *
 **/
@Component
public class DefaultOrcaRouterModelI implements IChatModel {
    private static final String ORCA_ROUTER_BASE_URL = "https://api.orcarouter.ai/v1";
    private final ChatModelTransportConfig transportConfig;

    public DefaultOrcaRouterModelI(ChatModelTransportConfig transportConfig) {
        this.transportConfig = transportConfig;
    }

    @Override
    public Model getModel(ModelConfigWrapper config) {
        if (config.getProvider() != getProvider()) {
            throw new IllegalArgumentException("The provider is not supported");
        }

        var options = GenerateOptionsHelper.create(config);
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .httpTransport(transportConfig.createChatTransport())
                .generateOptions(options);

        builder.baseUrl(effectiveBaseUrl(config));

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

        var options = GenerateOptionsHelper.create(config);
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelCode())
                .stream(config.getStreaming() != null && config.getStreaming())
                .httpTransport(HttpTransportHelper.createOkHttpTransport(10, 15))
                .generateOptions(options);

        builder.baseUrl(effectiveBaseUrl(config));

        return ThinkingToolChoiceModel.wrap(builder.build(), config, options);
    }

    @Override
    public ModelProviderType getProvider() {
        return ModelProviderType.ORCA_ROUTER;
    }

    @Override
    public int order() {
        return 0;
    }

    /**
     * OrcaRouter 默认走官方网关地址；未显式配置 baseUrl 时兜底到默认值
     */
    private String effectiveBaseUrl(ModelConfigWrapper config) {
        if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            return config.getBaseUrl();
        }
        return ORCA_ROUTER_BASE_URL;
    }
}
