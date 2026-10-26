package com.gmd.faq.server.main.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.model.ModelResilienceSettings;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentModelResiliencePolicy {
    private final ModelConfigService models;

    public void validate(AgentType type, Long primaryId, JsonNode config) {
        validate(type, primaryId, config, null);
    }

    public void validate(AgentType type, Long primaryId, JsonNode config, JsonNode primaryOverrides) {
        if (config == null || config.isNull()) return;
        if (type != AgentType.CUSTOM) throw new IllegalArgumentException("只有自定义智能体可配置模型容错");
        var settings = ModelResilienceSettings.parse(config);
        ModelWrapper primary = requireModel(primaryId);
        int contextWindow = primaryOverrides != null && primaryOverrides.path("contextWindow").canConvertToInt()
                ? primaryOverrides.path("contextWindow").asInt() : primary.getConfig().getContextWindow() == null ? 0 : primary.getConfig().getContextWindow();
        if (settings.fallbackModelId() != null) {
            if (settings.fallbackModelId().equals(primaryId)) throw new IllegalArgumentException("备用模型必须与主模型不同");
            var fallback = requireModel(settings.fallbackModelId());
            // 当前模型配置缺少独立的工具能力声明，首批只开放相同供应商类型的适配器。
            if (primary.getProvider().getType() != fallback.getProvider().getType()) {
                throw new IllegalArgumentException("备用模型需使用相同供应商类型以保持工具调用格式兼容");
            }
            primary.getConfig().getModelType().forEach(capability -> {
                if (!hasType(fallback, capability.asText())) {
                    throw new IllegalArgumentException("备用模型必须覆盖主模型声明的输入类型");
                }
            });
            if (contextWindow <= 0 || fallback.getConfig().getContextWindow() == null
                    || fallback.getConfig().getContextWindow() < contextWindow) {
                throw new IllegalArgumentException("备用模型上下文窗口不能小于主模型");
            }
        }
        if (settings.summaryModelId() != null) {
            var summary = requireModel(settings.summaryModelId());
            if (summary.getConfig().getContextWindow() == null || contextWindow <= 0
                    || summary.getConfig().getContextWindow() < contextWindow) {
                throw new IllegalArgumentException("摘要模型上下文窗口需覆盖主模型，以容纳待压缩历史");
            }
        }
    }

    private ModelWrapper requireModel(Long id) {
        ModelWrapper wrapper = models.getModelWrapperById(id);
        Long tenant = TenantUtils.getCurrentTenantId();
        if (tenant == null || !Objects.equals(tenant, wrapper.getConfig().getTenantId())
                || !Objects.equals(tenant, wrapper.getProvider().getTenantId())) {
            throw new IllegalArgumentException("模型不存在或不属于当前组织");
        }
        if (wrapper.getConfig().getModelType() == null || !wrapper.getConfig().getModelType().isArray()
                || !hasType(wrapper, "CHAT")) {
            throw new IllegalArgumentException("模型必须声明文本对话能力");
        }
        return wrapper;
    }
    private boolean hasType(ModelWrapper model, String type) {
        if (model.getConfig().getModelType() == null || !model.getConfig().getModelType().isArray()) return false;
        for (JsonNode capability : model.getConfig().getModelType()) if (type.equals(capability.asText())) return true;
        return false;
    }
}
