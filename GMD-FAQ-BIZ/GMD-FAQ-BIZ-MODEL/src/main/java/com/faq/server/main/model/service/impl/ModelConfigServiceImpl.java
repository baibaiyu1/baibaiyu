package com.gmd.faq.server.main.model.service.impl;

import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.ModelConfig;
import com.gmd.faq.server.main.common.entity.ModelProvider;
import com.gmd.faq.server.main.common.enums.ModelApiType;
import com.gmd.faq.server.main.common.enums.ModelProviderType;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.wrapper.ModelWrapper;
import com.gmd.faq.server.main.model.mapper.ModelConfigMapper;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import com.gmd.faq.server.main.model.service.ModelProviderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型配置Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final ModelProviderService modelProviderService;
    private final MessagePublisher messagePublisher;

    @Override
    public boolean save(ModelConfig entity) {
        if (entity.getApiType() == null) entity.setApiType(ModelApiType.CHAT_COMPLETIONS);
        if (entity.getResponsesSampling() == null) entity.setResponsesSampling(false);
        validateProtocol(entity, null);
        return super.save(entity);
    }

    private void validateProtocol(ModelConfig entity, ModelConfig current) {
        var protocol = entity.getApiType() != null ? entity.getApiType() : current == null ? null : current.getApiType();
        Long providerId = entity.getProviderId() != null ? entity.getProviderId() : current == null ? null : current.getProviderId();
        var provider = providerId == null ? null : modelProviderService.getById(providerId);
        if (protocol == ModelApiType.RESPONSES && (provider == null || provider.getType() != ModelProviderType.OPEN_AI))
            throw new IllegalArgumentException("Responses 协议仅支持 OpenAI 类型供应商");
        var extend = entity.getExtendConfig() != null ? entity.getExtendConfig() : current == null ? null : current.getExtendConfig();
        var reasoning = extend == null ? null : extend.get("reasoning");
        com.gmd.faq.server.main.common.model.ReasoningConfigSupport.rejectConflicts(extend, reasoning);
        if (provider != null) {
            var caps = com.gmd.faq.server.main.common.model.ReasoningConfigSupport.capabilities(provider.getType(), provider.getBaseUrl(),
                    entity.getModelId() != null ? entity.getModelId() : current == null ? null : current.getModelId(), protocol,
                    com.gmd.faq.server.main.common.model.ReasoningConfigSupport.profile(extend));
            com.gmd.faq.server.main.common.model.ReasoningConfigSupport.validate(
                    com.gmd.faq.server.main.common.model.ReasoningConfigSupport.parse(reasoning), caps,
                    entity.getMaxTokens() != null ? entity.getMaxTokens() : current == null ? null : current.getMaxTokens());
        }
    }

    @Override
    public ModelWrapper getModelWrapperById(Long id) {
        ModelConfig config = getById(id);
        if (config == null) {
            throw new RuntimeException("model config not found");
        }

        if (!config.getEnabled()) {
            throw new RuntimeException("model config is disabled");
        }

        ModelProvider modelProvider = modelProviderService.getById(config.getProviderId());
        if (modelProvider == null) {
            throw new RuntimeException("model provider not found");
        }

        if (!modelProvider.getEnabled()) {
            throw new RuntimeException("model provider is disabled");
        }

        return ModelWrapper.builder()
                .config(config)
                .provider(modelProvider)
                .build();
    }

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        ArrayList<Object> names = new ArrayList<>();
        getAgentDefinitions(ids).forEach(agentDefinition -> {
            names.add(agentDefinition.getName());
        });

        return names;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        // 删除前先获取关联的智能体ID，以便后续触发重新注册
        List<Long> agentIds = getAgentDefinitions(ids).stream().map(AgentDefinition::getId).toList();
        boolean result = removeByIds(ids);
        publishAgentReregister(agentIds);
        return result;
    }

    @Override
    public boolean doUpdate(ModelConfig entity) {
        ModelConfig current = getById(entity.getId());
        if (current == null) throw new IllegalArgumentException("模型配置不存在");
        validateProtocol(entity, current);
        boolean result = updateById(entity);
        List<Long> agentIds = getAgentDefinitions(List.of(entity.getId())).stream().map(AgentDefinition::getId).toList();
        publishAgentReregister(agentIds);
        return result;
    }

    private void publishAgentReregister(List<Long> agentIds) {
        agentIds.forEach(agentId ->
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(agentId)));
    }

    private List<AgentDefinition> getAgentDefinitions(List<Long> systemPromptId) {
        if (systemPromptId == null || systemPromptId.isEmpty()) {
            return new ArrayList<>();
        }

        String subSql = systemPromptId.stream().map(String::valueOf).collect(Collectors.joining(","));

        String jsonIds = systemPromptId.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        String sql = String.format("SELECT * FROM %s WHERE (model_config_id IN (%s)"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(model_resilience_config,'$.fallbackModelId')) IN (%s)"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(model_resilience_config,'$.summaryModelId')) IN (%s))",
                TableConst.AGENT, subSql, jsonIds, jsonIds);
        // 添加租户过滤（JdbcTemplate 绕过 MyBatis-Plus 拦截器）
        if (TenantUtils.getCurrentTenantId() != null) {
            sql += " AND tenant_id = " + TenantUtils.getCurrentTenantId();
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AgentDefinition agent = new AgentDefinition();
            // 手动映射字段
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            agent.setDescription(rs.getString("description"));
            return agent;
        });
    }
}
