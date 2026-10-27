package com.gmd.faq.server.main.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.entity.AgentKnowledgeBase;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.knowledge.mapper.KnowledgeBaseConfigMapper;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.knowledge.service.KnowledgeModelService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库配置Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseConfigServiceImpl extends ServiceImpl<KnowledgeBaseConfigMapper, KnowledgeBaseConfig> implements KnowledgeBaseConfigService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentKnowledgeBaseServiceImpl agentKnowledgeBaseService;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;
    private final KnowledgeModelService knowledgeModels;

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        List<Long> agentIds = agentKnowledgeBaseService.getAgentIds(ids);
        Set<String> visibleIds = resourceVisibilityService.visibilityMap("AGENT", agentIds).keySet();
        getAgentDefinitions(agentIds).forEach(agentDefinition -> {
            if (visibleIds.contains(agentDefinition.getId().toString())) {
                names.add(agentDefinition.getName());
            }
        });

        return names;
    }

    @Override
    public List<KnowledgeBaseConfig> getByAgentId(Long agentId) {
        List<Long> knowledgeIds = agentKnowledgeBaseService.getKnowledgeIds(agentId);

        if (knowledgeIds.isEmpty()) {
            return null;
        }

        return listByIds(knowledgeIds);
    }

    @Override
    public boolean save(KnowledgeBaseConfig entity) {
        knowledgeModels.validateReferences(entity);
        // 归属人由服务端写入：客户端传 ownerId / visibility 一律不作数
        ResourceOwnershipSupport.initOwnership(entity);
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        // 资源级判权：列表能看到 ≠ 能删，归属人或管理员才能删除
        listByIds(ids).forEach(config -> ResourceOwnershipSupport.assertManageable(config, "删除"));
        // 删除前先获取关联的智能体ID，以便后续触发重新注册
        List<Long> agentIds = agentKnowledgeBaseService.getAgentIds(ids);
        removeByIds(ids);
        boolean result = agentKnowledgeBaseService.remove(new LambdaQueryWrapper<AgentKnowledgeBase>().in(AgentKnowledgeBase::getKnowledgeBaseConfigId, ids));
        publishAgentReregister(agentIds);
        return result;
    }

    @Override
    public boolean doUpdate(KnowledgeBaseConfig entity) {
        KnowledgeBaseConfig current = getById(entity.getId());
        ResourceOwnershipSupport.assertManageable(current, "修改");
        if (entity.getKbType() == null) entity.setKbType(current.getKbType());
        if (entity.getConnectionConfig() == null) entity.setConnectionConfig(current.getConnectionConfig());
        if (current.getConnectionConfig() != null && current.getConnectionConfig().path("platformModelsManaged").asBoolean()
                && entity.getConnectionConfig() instanceof com.fasterxml.jackson.databind.node.ObjectNode connection)
            connection.put("platformModelsManaged", true);
        knowledgeModels.validateReferences(entity);
        if (current.getKbType() == com.gmd.faq.server.main.common.enums.KbType.LOCAL
                && !java.util.Objects.equals(current.getConnectionConfig().path("dimension"), entity.getConnectionConfig().path("dimension")))
            throw new IllegalArgumentException("本地知识库创建后不能修改向量维度，请新建知识库");
        if (current.getKbType() == com.gmd.faq.server.main.common.enums.KbType.LOCAL
                && !java.util.Objects.equals(current.getConnectionConfig(), entity.getConnectionConfig())) {
            boolean changed = List.of("embeddingModelId", "embeddingIdentity", "embeddingModel", "providerType", "baseUrl").stream()
                    .anyMatch(key -> !java.util.Objects.equals(current.getConnectionConfig().path(key), entity.getConnectionConfig().path(key)));
            if (changed) {
                Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TableConst.RAG_DOCUMENT
                        + " WHERE knowledge_base_config_id = ? AND tenant_id = ?", Long.class, current.getId(), TenantUtils.getCurrentTenantId());
                if (count == null || count > 0) throw new IllegalArgumentException("已有文档的知识库不能更换向量模型，请先清空文档或新建知识库");
            }
        }
        // 归属与可见性只能走「可见性」专用接口，避免被 update 顺带篡改
        entity.setOwnerId(null);
        entity.setVisibility(null);
        boolean result = updateById(entity);
        publishAgentReregister(agentKnowledgeBaseService.getAgentIds(List.of(entity.getId())));
        return result;
    }

    private void publishAgentReregister(List<Long> agentIds) {
        agentIds.forEach(agentId ->
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(agentId)));
    }

    private List<AgentDefinition> getAgentDefinitions(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return new ArrayList<>();
        }

        String subSql = agentIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        String sql = String.format("SELECT * FROM %s WHERE id IN (%s)", TableConst.AGENT, subSql);
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
