package com.gmd.faq.server.main.skill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.AgentSkillPackage;
import com.gmd.faq.server.main.common.entity.SkillPackage;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.SkillPackageVO;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.skill.mapper.SkillPackageMapper;
import com.gmd.faq.server.main.skill.service.AgentSkillPackageService;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import com.gmd.faq.server.main.skill.service.SkillToolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能包Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class SkillPackageServiceImpl extends ServiceImpl<SkillPackageMapper, SkillPackage> implements SkillPackageService {
    private final JdbcTemplate jdbcTemplate;
    private final AgentSkillPackageService agentSkillPackageService;
    private final SkillToolService skillToolService;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        List<Long> agentIds = agentSkillPackageService.getAgentIds(ids);
        Set<String> visibleIds = resourceVisibilityService.visibilityMap("AGENT", agentIds).keySet();
        getAgentDefinitions(agentIds).forEach(agentDefinition -> {
            if (visibleIds.contains(agentDefinition.getId().toString())) {
                names.add(agentDefinition.getName());
            }
        });

        return names;
    }

    @Override
    public List<String> listCategories() {
        return this.lambdaQuery()
                .select(SkillPackage::getCategory)
                .isNotNull(SkillPackage::getCategory)
                .groupBy(SkillPackage::getCategory)
                .list()
                .stream()
                .map(SkillPackage::getCategory)
                .filter(category -> category != null && !category.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean save(SkillPackage entity) {
        // 归属人由服务端写入（新建技能与各导入入口都走这里），客户端传 ownerId / visibility 不作数
        ResourceOwnershipSupport.initOwnership(entity);
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        // 删除前查询技能包信息，用于发布文件删除事件
        List<SkillPackage> skills = listByIds(ids);
        // 资源级判权：列表能看到 ≠ 能删，归属人或管理员才能删除
        skills.forEach(skill -> ResourceOwnershipSupport.assertManageable(skill, "删除"));
        // 删除前先获取关联的智能体ID，以便后续触发重新注册
        List<Long> agentIds = agentSkillPackageService.getAgentIds(ids);
        removeByIds(ids);
        // 删除技能包与智能体的关联
        agentSkillPackageService.remove(new LambdaQueryWrapper<AgentSkillPackage>().in(AgentSkillPackage::getSkillPackageId, ids));
        // 删除技能包与工具的关联
        skillToolService.deleteSkillTool(ids);
        publishAgentReregister(agentIds);
        // 发布文件删除同步事件
        skills.forEach(skill -> publishSkillFileDelete(skill.getName(), skill.getTenantId()));
        return true;
    }

    @Override
    public boolean doUpdate(SkillPackage entity) {
        ResourceOwnershipSupport.assertManageable(getById(entity.getId()), "修改");
        // 归属与可见性只能走「可见性」专用接口，避免被 update 顺带篡改
        entity.setOwnerId(null);
        entity.setVisibility(null);
        boolean result = updateById(entity);
        publishAgentReregister(agentSkillPackageService.getAgentIds(List.of(entity.getId())));
        return result;
    }

    @Override
    public boolean updateAlias(Long id, String alias) {
        requireManageable(id);
        // 仅更新别名，不触发智能体重新注册
        return update(new LambdaUpdateWrapper<SkillPackage>()
                .eq(SkillPackage::getId, id)
                .set(SkillPackage::getAlias, alias));
    }

    @Override
    public SkillPackage requireManageable(Long id) {
        return ResourceOwnershipSupport.assertManageable(getById(id), "编辑技能文件");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateTools(Long id, List<Long> toolIds) {
        requireManageable(id);
        if (toolIds != null) {
            toolIds.stream().distinct().forEach(toolId -> resourceVisibilityService.assertUsable("TOOL", toolId));
        }
        boolean updated = skillToolService.saveSkillTool(id, toolIds);
        if (updated) refreshBoundAgents(id);
        return updated;
    }

    @Override
    public void refreshBoundAgents(Long id) {
        publishAgentReregister(agentSkillPackageService.getAgentIds(List.of(id)));
    }

    @Override
    public SkillPackageVO getDetail(Long id) {
        SkillPackage entity = getById(id);
        if (entity == null) {
            return null;
        }
        SkillPackageVO vo = new SkillPackageVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setAlias(entity.getAlias());
        vo.setDescription(entity.getDescription());
        vo.setCategory(entity.getCategory());
        vo.setEnabled(entity.getEnabled());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setUpdatedBy(entity.getUpdatedBy());
        // 查询关联的工具ID列表
        vo.setTools(skillToolService.getToolIds(id));
        return vo;
    }

    private void publishAgentReregister(List<Long> agentIds) {
        agentIds.forEach(agentId ->
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(agentId)));
    }

    /**
     * 发布技能文件删除事件（action=delete）
     */
    private void publishSkillFileDelete(String skillName, Long tenantId) {
        String tenantCode = queryTenantCode(tenantId);
        if (tenantCode == null) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("action", "delete");
        payload.put("skillName", skillName);
        payload.put("tenantCode", tenantCode);
        try {
            String json = objectMapper.writeValueAsString(payload);
            messagePublisher.publishAfterCommit(RedisChannelTopic.SKILL_FILE_SYNC_CHANNEL, json);
        } catch (Exception e) {
            // JSON 序列化失败（理论上不会发生）
        }
    }

    /**
     * 根据租户ID查询租户编码
     */
    private String queryTenantCode(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM gmd_faq_tenant WHERE id = ?", String.class, tenantId);
        return codes.isEmpty() ? null : codes.get(0);
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
