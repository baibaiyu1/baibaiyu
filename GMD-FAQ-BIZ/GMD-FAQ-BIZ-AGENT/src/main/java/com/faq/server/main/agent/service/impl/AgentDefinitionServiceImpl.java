package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.a2a.service.AgentA2aService;
import com.gmd.faq.server.main.agent.mapper.AgentDefinitionMapper;
import com.gmd.faq.server.main.agent.mapper.AgentTagMapper;
import com.gmd.faq.server.main.agent.mapper.IJobInfoMapper;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.AgentSubAgentService;
import com.gmd.faq.server.main.agent.service.AgentModelResiliencePolicy;
import com.gmd.faq.server.main.approval.service.ApprovalService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.AgentType;
import com.gmd.faq.server.main.common.enums.ApprovalResourceType;
import com.gmd.faq.server.main.common.enums.ModelType;
import com.gmd.faq.server.main.common.enums.ResourceVisibility;
import com.gmd.faq.server.main.common.enums.ToolChoiceStrategy;
import com.gmd.faq.server.main.common.enums.workflow.WorkflowStatus;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.AgentTagSupport;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.gmd.faq.server.main.hook.service.AgentHookService;
import com.gmd.faq.server.main.knowledge.service.AgentKnowledgeBaseService;
import com.gmd.faq.server.main.mcp.service.AgentMcpServerService;
import com.gmd.faq.server.main.model.service.ModelConfigService;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.prompt.service.SystemPromptTemplateService;
import com.gmd.faq.server.main.skill.service.AgentSkillPackageService;
import com.gmd.faq.server.main.skill.service.SkillPackageService;
import com.gmd.faq.server.main.studio.service.AgentStudioService;
import com.gmd.faq.server.main.longterm.service.AgentLongTermMemoryService;
import com.gmd.faq.server.main.tool.service.AgentToolService;
import com.gmd.faq.server.main.agent.service.AgentCodeExecutionService;
import com.gmd.faq.server.main.workflowbiz.service.AgentWorkflowService;
import com.gmd.faq.server.main.workflowbiz.service.WorkflowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.tool.service.ToolService;
import lombok.RequiredArgsConstructor;
import io.agentscope.core.model.StructuredOutputReminder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 智能体定义Service实现
 *
 */
@Service
@RequiredArgsConstructor
public class AgentDefinitionServiceImpl extends ServiceImpl<AgentDefinitionMapper, AgentDefinition> implements AgentDefinitionService {

    /** 推荐问题（开场问题）最大条数，与对话端展示口径一致 */
    private static final int MAX_OPENING_QUESTIONS = 6;

    /** 仅用于组装 uiConfig 这类小 JSON，无时间/枚举序列化需求 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final AgentHookService agentHookService;
    private final AgentToolService agentToolService;
    private final ToolService toolService;
    private final AgentMcpServerService agentMcpServerService;
    private final AgentSkillPackageService agentSkillPackageService;
    private final SkillPackageService skillPackageService;
    private final AgentSubAgentService agentSubAgentService;
    private final AgentKnowledgeBaseService agentKnowledgeBaseService;
    private final ModelConfigService modelConfigService;
    private final ParamsAdapter paramsAdapter;
    private final SystemPromptTemplateService systemPromptTemplateService;
    private final AgentA2aService agentA2aService;
    private final AgentStudioService agentStudioService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final IJobInfoMapper iJobInfoMapper;
    private final AgentCodeExecutionService agentCodeExecutionService;
    private final AgentWorkflowService agentWorkflowService;
    private final WorkflowService workflowService;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;
    private final ApprovalService approvalService;
    private final AgentChatKeyRevoker chatKeyRevoker;
    private final AgentTagMapper agentTagMapper;
    private final AgentModelResiliencePolicy modelResiliencePolicy;
    private final com.gmd.faq.server.main.agent.service.AgentReasoningPolicy reasoningPolicy;

    @Override
    public AgentDefinitionVO agentDefinitionDetail(Long id) {
        AgentDefinition entity = getById(id);
        if (entity == null) {
            throw new RuntimeException("AgentDefinition not found for id: " + id);
        }
        resourceVisibilityService.assertViewable("AGENT", id);

        AgentDefinitionVO vo = BeanUtils.copy(entity, AgentDefinitionVO.class);
        // 停用的模型仍允许编辑智能体；详情不因该依赖失效而不可读。
        vo.setReasoningConfig(reasoningPolicy.resolve(vo));
        vo.setTags(AgentTagSupport.tagsOf(entity));

        vo.setHook(agentHookService.getHookIds(id));
        Long studioConfigId = agentStudioService.getStudioIdByAgentId(id);
        if (studioConfigId != null) {
            vo.setStudioConfigId(studioConfigId);
        }
        Long codeExecutionId = agentCodeExecutionService.getCodeExecutionIdByAgentId(id);
        if (codeExecutionId != null) {
            vo.setCodeExecutionConfigId(codeExecutionId);
        }
        Long longTermMemoryConfigId = agentLongTermMemoryService.getConfigIdByAgentId(id);
        if (longTermMemoryConfigId != null) {
            vo.setLongTermMemoryConfigId(longTermMemoryConfigId);
        }

        if(entity.getAgentType() == AgentType.CUSTOM) {
            vo.setTool(agentToolService.getToolIds(id));
            vo.setMcp(agentMcpServerService.getMcpIds(id));
            vo.setMcpBindings(agentMcpServerService.getBindings(id));
            vo.setSkill(agentSkillPackageService.getSkillPackageIds(id));
            vo.setSubAgent(agentSubAgentService.getSubAgentIds(id));
            vo.setKnowledgeBase(agentKnowledgeBaseService.getKnowledgeIds(id));
            vo.setWorkflow(agentWorkflowService.getWorkflowIds(id));
        } else {
            vo.setAgentA2A(agentA2aService.getA2aConfigByAgentId(id));
        }

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveAgentDefinition(AgentDefinitionVO vo) {
        initializeCreationDefaults(vo);
        com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings.parse(vo.getRagConfig());
        checkModelResilience(vo, null);
        AgentDefinition agentDefinition = BeanUtils.copy(vo, AgentDefinition.class);
        AgentTagSupport.setTags(agentDefinition, submittedTags(vo, null));
        ensureTags(AgentTagSupport.tagsOf(agentDefinition));
        if (Boolean.TRUE.equals(agentDefinition.getFollowTemplate())) {
            agentDefinition.setSystemPrompt(followedPrompt(agentDefinition.getSystemPromptTemplateId()));
        }
        // 归属人与可见性一律由服务端写入：客户端传 ownerId / visibility 不作数（含 Fork 副本）
        ResourceOwnershipSupport.initOwnership(agentDefinition);
        agentDefinition.setPublished(false);
        agentDefinition.setExternalShareEnabled(false);
        save(agentDefinition);
        vo.setId(agentDefinition.getId());

        saveSubItems(vo);

        return true;
    }

    /** 基本信息即可创建停用草稿，完整配置在设计页维护。 */
    private void initializeCreationDefaults(AgentDefinitionVO vo) {
        if (vo.getAgentCode() == null || vo.getAgentCode().isBlank()) {
            String tenantCode = UserUtils.getTenantCode();
            String prefix = tenantCode == null || tenantCode.isBlank() ? "" : tenantCode + "-";
            vo.setAgentCode(prefix + "agent-" + IdWorker.getIdStr());
        }
        if (vo.getAgentType() == null) vo.setAgentType(AgentType.CUSTOM);
        if (vo.getEnabled() == null) vo.setEnabled(false);
        if (vo.getFollowTemplate() == null) vo.setFollowTemplate(false);
        if (vo.getToolChoiceStrategy() == null) vo.setToolChoiceStrategy(ToolChoiceStrategy.AUTO);
        if (vo.getMaxIterations() == null) vo.setMaxIterations(50);
        if (vo.getMaxSubtasks() == null) vo.setMaxSubtasks(10);
        if (vo.getEnablePlanning() == null) vo.setEnablePlanning(false);
        if (vo.getRequirePlanConfirmation() == null) vo.setRequirePlanConfirmation(false);
        if (vo.getEnableMemory() == null) vo.setEnableMemory(vo.getAgentType() == AgentType.CUSTOM);
        if (vo.getShowToolProcess() == null) vo.setShowToolProcess(false);
        if (vo.getSensitiveFilterEnabled() == null) vo.setSensitiveFilterEnabled(false);
        if (vo.getEnableMemoryCompression() == null) vo.setEnableMemoryCompression(false);
        if (vo.getStructuredOutputEnabled() == null) vo.setStructuredOutputEnabled(false);
        if (vo.getStructuredOutputReminder() == null) vo.setStructuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE);
        if (vo.getVersion() == null) vo.setVersion("1.0.0");
        // 关联保存方法要求非空集合；创建弹窗不加载或提交资源配置。
        if (vo.getHook() == null) vo.setHook(List.of());
        if (vo.getTool() == null) vo.setTool(List.of());
        if (vo.getSkill() == null) vo.setSkill(List.of());
        if (vo.getWorkflow() == null) vo.setWorkflow(List.of());
        if (vo.getKnowledgeBase() == null) vo.setKnowledgeBase(List.of());
        if (vo.getMcp() == null) vo.setMcp(List.of());
        if (vo.getSubAgent() == null) vo.setSubAgent(List.of());
        if (vo.getAgentType() == AgentType.A2A && vo.getAgentA2A() != null
                && vo.getAgentA2A().getA2aConfig() instanceof ObjectNode config
                && config.path("agentName").asText("").isBlank()) {
            config.put("agentName", vo.getAgentCode());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentDefinitionVO forkAgent(Long sourceId, String name) {
        // 先判存在：agentDefinitionDetail 对不存在的ID直接抛异常，会被全局兜底成「系统异常」
        if (sourceId == null || getById(sourceId) == null) {
            throw new IllegalStateException("源智能体不存在");
        }
        AgentDefinitionVO source = agentDefinitionDetail(sourceId);
        if (source == null) {
            throw new IllegalStateException("源智能体不存在");
        }
        if (AgentType.A2A.equals(source.getAgentType())) {
            throw new IllegalStateException("A2A 智能体暂不支持 Fork");
        }
        resourceVisibilityService.assertUsable("AGENT", sourceId);

        AgentDefinitionVO copy = BeanUtils.copy(source, AgentDefinitionVO.class);
        if (copy == null) {
            throw new IllegalStateException("复制智能体失败");
        }
        copy.setId(null);
        copy.setName(buildForkName(source.getName(), name));
        copy.setAgentCode(buildForkCode(source.getAgentCode()));
        // 副本默认停用：确认配置后再由使用者启用，避免未经确认即注册进运行时
        copy.setEnabled(false);
        // 副本一律私有：可见性由 initOwnership 统一写死为 PRIVATE
        copy.setVisibility(ResourceVisibility.PRIVATE.name());
        copy.setOwnerId(UserUtils.getId());
        copy.setForkFromId(source.getId());
        copy.setForkRootId(source.getForkRootId() == null ? source.getId() : source.getForkRootId());
        // 派生数据不随副本复制
        copy.setAgentA2A(null);
        copy.setJobInfo(null);
        copy.setUsed(null);
        if (!UserUtils.isAdmin()) copy.setModelResilienceConfig(null);

        saveAgentDefinition(copy);
        return agentDefinitionDetail(copy.getId());
    }

    /**
     * 生成副本名称
     */
    private String buildForkName(String sourceName, String customName) {
        if (customName != null && !customName.isBlank()) {
            String trimmed = customName.trim();
            return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
        }
        String base = (sourceName == null || sourceName.isBlank()) ? "智能体" : sourceName;
        String candidate = base + " 副本";
        return candidate.length() > 100 ? candidate.substring(0, 100) : candidate;
    }

    /**
     * 生成副本智能体编号（agentCode 长度上限 100，用于 AG-UI 运行路径，需保持唯一）
     */
    private String buildForkCode(String sourceCode) {
        String base = (sourceCode == null || sourceCode.isBlank()) ? "agent" : sourceCode;
        String suffix = "copy" + (System.currentTimeMillis() % 100000);
        int maxBaseLength = 100 - suffix.length() - 1;
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return base + "-" + suffix;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateAgentDefinition(AgentDefinitionVO vo) {
        AgentDefinition oldAgent = getById(vo.getId());
        // 资源级判权：列表能看到 ≠ 能改，归属人或管理员才能修改
        ResourceOwnershipSupport.assertManageable(oldAgent, "修改");
        if (vo.getAgentCode() == null) {
            if (vo.getEnabled() == null) {
                throw new IllegalArgumentException("启停状态不能为空");
            }
            List<JobInfo> agent = iJobInfoMapper.selectList(
                    new LambdaQueryWrapper<JobInfo>()
                            .eq(JobInfo::getType, "AGENT")
                            .eq(JobInfo::getBizId, vo.getId()));
            // 一个智能体可以绑定多个定时任务，只要有任意一个还启用着就不允许禁用智能体
            if (!vo.getEnabled() && agent.stream().anyMatch(job -> Boolean.TRUE.equals(job.getEnabled()))) {
                throw new RuntimeException("请先禁用定时任务");
            }
            AgentDefinition enabledOnly = new AgentDefinition();
            enabledOnly.setId(vo.getId());
            enabledOnly.setEnabled(vo.getEnabled());
            updateById(enabledOnly);
            if (vo.getEnabled()) {
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(vo.getId()));
            } else {
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_UNREGISTER_CHANNEL, oldAgent.getAgentCode());
            }

            return true;
        }

        AgentPublicationSnapshot approvedConfig = AgentPublicationSnapshot.of(agentDefinitionDetail(vo.getId()));
        com.gmd.faq.server.main.common.model.KnowledgeRetrievalSettings.parse(vo.getRagConfig());
        checkModelResilience(vo, oldAgent);
        // 归属与可见性只能走「可见性」专用接口，避免被 update 顺带篡改
        vo.setOwnerId(null);
        vo.setVisibility(null);
        vo.setPublished(null);
        vo.setExternalShareEnabled(null);
        boolean followsTemplate = vo.getFollowTemplate() == null
                ? Boolean.TRUE.equals(oldAgent.getFollowTemplate())
                : Boolean.TRUE.equals(vo.getFollowTemplate());
        if (followsTemplate) {
            Long templateId = vo.getSystemPromptTemplateId() == null
                    ? oldAgent.getSystemPromptTemplateId() : vo.getSystemPromptTemplateId();
            vo.setSystemPrompt(followedPrompt(templateId));
        }
        AgentDefinition updatedAgent = BeanUtils.copy(vo, AgentDefinition.class);
        AgentTagSupport.setTags(updatedAgent, submittedTags(vo, oldAgent));
        ensureTags(AgentTagSupport.tagsOf(updatedAgent));
        // 完整配置保存允许清空可选字段；不能改为实体全局 ALWAYS，否则启停等局部更新会误清配置。
        LambdaUpdateWrapper<AgentDefinition> clearedFields = new LambdaUpdateWrapper<AgentDefinition>()
                .eq(AgentDefinition::getId, vo.getId())
                .set(vo.getModelParamsOverride() == null && oldAgent.getModelParamsOverride() != null,
                        AgentDefinition::getModelParamsOverride, null)
                .set(vo.getModelResilienceConfig() == null && oldAgent.getModelResilienceConfig() != null,
                        AgentDefinition::getModelResilienceConfig, null)
                .set(vo.getRagConfig() == null && oldAgent.getRagConfig() != null,
                        AgentDefinition::getRagConfig, null)
                .set(vo.getMemoryCompressionConfig() == null && oldAgent.getMemoryCompressionConfig() != null,
                        AgentDefinition::getMemoryCompressionConfig, null)
                .set(vo.getStructuredOutputSchema() == null && oldAgent.getStructuredOutputSchema() != null,
                        AgentDefinition::getStructuredOutputSchema, null)
                .set(!followsTemplate && vo.getSystemPromptTemplateId() == null && oldAgent.getSystemPromptTemplateId() != null,
                        AgentDefinition::getSystemPromptTemplateId, null);
        if (clearedFields.getSqlSet() == null) {
            updateById(updatedAgent);
        } else {
            update(updatedAgent, clearedFields);
        }
        saveSubItems(vo);

        if (!approvedConfig.equals(AgentPublicationSnapshot.of(agentDefinitionDetail(vo.getId())))) {
            approvalService.cancelPendingForResource(ApprovalResourceType.AGENT, vo.getId(),
                    "智能体配置已变更，请重新提交发布申请");
            AgentDefinition unpublished = new AgentDefinition();
            unpublished.setId(vo.getId());
            unpublished.setPublished(false);
            unpublished.setExternalShareEnabled(false);
            updateById(unpublished);
            chatKeyRevoker.revoke(oldAgent);
        }

        // Agent Code 发生变化，将旧的 Agent 注销
        if (!oldAgent.getAgentCode().equals(vo.getAgentCode())) {
            messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_UNREGISTER_CHANNEL, oldAgent.getAgentCode());
        }

        messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(vo.getId()));
        return true;
    }

    private String followedPrompt(Long templateId) {
        if (templateId == null) {
            throw new IllegalArgumentException("跟随模板更新时必须选择提示词模板");
        }
        SystemPromptTemplate template = systemPromptTemplateService.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("提示词模板不存在或不属于当前组织");
        }
        return template.getContent();
    }

    private void checkModelResilience(AgentDefinitionVO vo, AgentDefinition existing) {
        JsonNode previous = existing == null ? null : existing.getModelResilienceConfig();
        if (!java.util.Objects.equals(previous, vo.getModelResilienceConfig()) && !UserUtils.isAdmin()) {
            throw new IllegalStateException("仅管理员可修改模型容错配置");
        }
        reasoningPolicy.validate(vo);
        if (vo.getModelResilienceConfig() != null) {
            modelResiliencePolicy.validate(vo.getAgentType(), vo.getModelConfigId(), vo.getModelResilienceConfig(), vo.getModelParamsOverride());
        }
    }

    private List<String> submittedTags(AgentDefinitionVO vo, AgentDefinition existing) {
        List<String> tags = vo.getTags();
        if (tags == null) {
            tags = vo.getTag() != null ? List.of(vo.getTag())
                    : existing != null ? AgentTagSupport.tagsOf(existing) : List.of();
        }
        return AgentTagSupport.normalize(tags);
    }

    private void ensureTags(List<String> names) {
        names.forEach(this::createTag);
    }

    private void saveSubItems(AgentDefinitionVO vo) {
        assertUsableDependencies(vo);
        agentHookService.saveAgentHook(vo.getId(), vo.getHook());
        if (vo.getAgentType() == AgentType.CUSTOM) {
            agentSubAgentService.saveSubAgent(vo.getId(), vo.getSubAgent());
            agentToolService.saveAgentTool(vo.getId(), vo.getTool());
            agentMcpServerService.saveAgentMcpServer(vo.getId(), vo.getMcp(), vo.getMcpBindings());
            agentSkillPackageService.saveAgentSkillPackage(vo.getId(), vo.getSkill());
            agentKnowledgeBaseService.saveAgentKnowledge(vo.getId(), vo.getKnowledgeBase());
            agentWorkflowService.saveAgentWorkflow(vo.getId(), vo.getWorkflow());
            if (vo.getStudioConfigId() != null) {
                agentStudioService.saveAgentStudio(vo.getId(), List.of(vo.getStudioConfigId()));
            } else {
                agentStudioService.deleteAgentStudio(List.of(vo.getId()));
            }
            if (vo.getCodeExecutionConfigId() != null) {
                agentCodeExecutionService.saveAgentCodeExecution(vo.getId(), List.of(vo.getCodeExecutionConfigId()));
            } else {
                agentCodeExecutionService.deleteAgentCodeExecution(List.of(vo.getId()));
            }
            if (vo.getLongTermMemoryConfigId() != null) {
                agentLongTermMemoryService.saveAgentLongTermMemory(vo.getId(), vo.getLongTermMemoryConfigId());
            } else {
                agentLongTermMemoryService.deleteAgentLongTermMemory(List.of(vo.getId()));
            }
        } else {
            AgentA2A agentA2A = vo.getAgentA2A();
            agentA2A.setAgentDefinitionId(vo.getId());
            agentA2aService.saveA2aConfig(agentA2A);
        }
    }

    private void assertUsableDependencies(AgentDefinitionVO vo) {
        if (vo.getAgentType() != AgentType.CUSTOM) {
            return;
        }
        assertUsableIds("AGENT", vo.getSubAgent());
        assertUsableIds("TOOL", vo.getTool());
        assertUsableIds("MCP", vo.getMcp());
        assertUsableIds("SKILL", vo.getSkill());
        assertUsableIds("KNOWLEDGE", vo.getKnowledgeBase());
    }

    private void assertUsableIds(String type, List<Long> ids) {
        if (ids != null) {
            ids.forEach(id -> resourceVisibilityService.assertUsable(type, id));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteAgentDefinition(List<Long> ids) {
        List<JobInfo> agent = iJobInfoMapper.selectList(
                new LambdaQueryWrapper<JobInfo>()
                        .eq(JobInfo::getType, "AGENT")
                        .in(JobInfo::getBizId, ids));
        if (!agent.isEmpty()) {
            throw new RuntimeException("请先解绑定时任务");
        }

        List<AgentDefinition> agents = listByIds(ids);
        // 资源级判权：列表能看到 ≠ 能删，归属人或管理员才能删除
        agents.forEach(item -> ResourceOwnershipSupport.assertManageable(item, "删除"));

        removeByIds(ids);
        agentA2aService.deleteA2aConfig(ids);
        agentSubAgentService.deleteSubAgent(ids);
        agentHookService.deleteAgentHook(ids);
        agentToolService.deleteAgentTool(ids);
        agentMcpServerService.deleteAgentMcpServer(ids);
        agentSkillPackageService.deleteAgentSkillPackage(ids);
        agentKnowledgeBaseService.deleteAgentKnowledge(ids);
        agentWorkflowService.deleteAgentWorkflow(ids);
        agentStudioService.deleteAgentStudio(ids);
        agentCodeExecutionService.deleteAgentCodeExecution(ids);
        agentLongTermMemoryService.deleteAgentLongTermMemory(ids);

        for (AgentDefinition agent_ : agents) {
            messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_UNREGISTER_CHANNEL, agent_.getAgentCode());
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveUiConfig(Long id, String openingMessage, List<String> openingQuestions) {
        AgentDefinition agent = getById(id);
        if (agent == null) {
            return Boolean.FALSE;
        }
        ResourceOwnershipSupport.assertManageable(agent, "修改");

        // 对话端展示配置为 JSON（text 列 + JsonNodeTypeHandler），此处做局部合并：
        // 未传的字段保持原值，避免「只改开场白」把推荐问题清空
        JsonNode existing = agent.getUiConfig();
        ObjectNode config = existing instanceof ObjectNode node
                ? node.deepCopy()
                : JSON_MAPPER.createObjectNode();

        if (openingMessage != null) {
            config.put("openingMessage", openingMessage);
        }
        if (openingQuestions != null) {
            ArrayNode questions = config.putArray("openingQuestions");
            openingQuestions.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    // 参照页口径：最多 6 条推荐问题
                    .limit(MAX_OPENING_QUESTIONS)
                    .forEach(questions::add);
        }

        AgentDefinition update = new AgentDefinition();
        update.setId(id);
        update.setUiConfig(config);
        return updateById(update);
    }

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {        List<Object> names = new ArrayList<>();
        ids.forEach(id -> {
            resourceVisibilityService.assertViewable("AGENT", id);
            agentSubAgentService.getSubAgentIds(id).forEach(subAgentId -> {
                AgentDefinition agentDefinition = getById(subAgentId);
                if (agentDefinition != null && resourceVisibilityService.canView("AGENT", subAgentId)) {
                    names.add(agentDefinition.getName());
                }
            });
        });

        return names;
    }

    @Override
    public List<String> listTags() {
        return agentTagMapper.selectList(new QueryWrapper<AgentTag>()
                        .select("name")
                        .orderByAsc("name")).stream()
                .map(AgentTag::getName)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createTag(String name) {
        List<String> normalized = AgentTagSupport.normalize(List.of(name == null ? "" : name));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        String value = normalized.getFirst();
        QueryWrapper<AgentTag> query = new QueryWrapper<AgentTag>()
                .eq("name", value);
        AgentTag existing = agentTagMapper.selectOne(query);
        if (existing != null) {
            return existing.getName();
        }
        AgentTag tag = new AgentTag();
        tag.setName(value);
        try {
            agentTagMapper.insert(tag);
            return value;
        } catch (DuplicateKeyException duplicate) {
            return value;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteTag(String name) {
        List<String> normalized = AgentTagSupport.normalize(List.of(name == null ? "" : name));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        String value = normalized.getFirst();
        AgentTag existing = agentTagMapper.selectOne(new QueryWrapper<AgentTag>().eq("name", value));
        if (existing == null) {
            return false;
        }

        List<AgentDefinition> agents = list(new QueryWrapper<AgentDefinition>()
                .select("id", "name", "tag", "tags_json"));
        for (AgentDefinition agent : agents) {
            if (AgentTagSupport.tagsOf(agent).contains(value)) {
                String agentName = agent.getName() == null || agent.getName().isBlank()
                        ? String.valueOf(agent.getId()) : agent.getName();
                throw new IllegalStateException("标签“" + value + "”仍被智能体“" + agentName
                        + "”使用，请先从智能体中移除该标签");
            }
        }
        agentTagMapper.deleteById(existing.getId());
        return true;
    }

    @Override
    public List<String> allowFileType(Long id) {
        resourceVisibilityService.assertUsable("AGENT", id);
        AgentDefinition agentDefinition = getById(id);
        if (agentDefinition == null || !agentDefinition.getEnabled()) {
            return List.of();
        }

        ModelConfig modelConfig = modelConfigService.getById(agentDefinition.getModelConfigId());
        if (modelConfig == null) {
            return List.of();
        }
        JsonNode modelTypeJ = modelConfig.getModelType();
        if (modelTypeJ == null) {
            return List.of();
        }

        List<String> modelType = parseModelType(modelTypeJ);
        List<String> allTypes = new ArrayList<>();
        // 现有多模态类型（受模型能力控制）
        if (modelType.contains(ModelType.IMAGE.name())) {
            allTypes.add(paramsAdapter.getValue("ALLOW_IMAGE_FILE_TYPE"));
        }
        if (modelType.contains(ModelType.AUDIO.name())) {
            allTypes.add(paramsAdapter.getValue("ALLOW_AUDIO_FILE_TYPE"));
        }
        if (modelType.contains(ModelType.VIDEO.name())) {
            allTypes.add(paramsAdapter.getValue("ALLOW_VIDEO_FILE_TYPE"));
        }

        // 无条件追加文档类型（框架能力，与模型多模态能力无关）— 必须在空判断之前
        allTypes.add(paramsAdapter.getValue("ALLOW_DOC_FILE_TYPE"));
        allTypes.add(paramsAdapter.getValue("ALLOW_EXCEL_FILE_TYPE"));
        allTypes.add(paramsAdapter.getValue("ALLOW_PPT_FILE_TYPE"));

        if (allTypes.isEmpty()) {
            return List.of();
        }

        String join = String.join(",", allTypes);
        return List.of(join.split(","));
    }

    @Override
    public List<ToolConfig> getEnabledToolsOfAgent(Long agentId) {
        resourceVisibilityService.assertUsable("AGENT", agentId);
        List<Long> toolIds = agentToolService.getToolIds(agentId);
        if (!toolIds.isEmpty()) {
            return toolService.list(
                    new LambdaQueryWrapper<ToolConfig>()
                            .select(ToolConfig::getId, ToolConfig::getName, ToolConfig::getToolId, ToolConfig::getDescription)
                            .eq(ToolConfig::getEnabled, true)
                            .in(ToolConfig::getId, toolIds));
        }
        return List.of();
    }

    @Override
    public List<SkillPackage> getEnabledSkillsOfAgent(Long agentId) {
        resourceVisibilityService.assertUsable("AGENT", agentId);
        List<Long> skillPackageIds = agentSkillPackageService.getSkillPackageIds(agentId);
        if (!skillPackageIds.isEmpty()) {
            return skillPackageService.list(
                    new LambdaQueryWrapper<SkillPackage>()
                            .select(SkillPackage::getId, SkillPackage::getName, SkillPackage::getDescription, SkillPackage::getAlias)
                            .eq(SkillPackage::getEnabled, true)
                            .in(SkillPackage::getId, skillPackageIds));
        }
        return Collections.emptyList();
    }

    @Override
    public List<Workflow> getEnabledWorkflowsOfAgent(Long agentId) {
        resourceVisibilityService.assertUsable("AGENT", agentId);
        List<Long> workflowIds = agentWorkflowService.getWorkflowIds(agentId);
        if (!workflowIds.isEmpty()) {
            return workflowService.list(
                    new LambdaQueryWrapper<Workflow>()
                            .select(Workflow::getId, Workflow::getName, Workflow::getRemark)
                            .eq(Workflow::getEnabled, true)
                            .eq(Workflow::getStatus, WorkflowStatus.PUBLISHED)
                            .in(Workflow::getId, workflowIds));
        }
        return Collections.emptyList();
    }

    private List<String> parseModelType(JsonNode modelTypeJ) {
        try {
            return (List<String>)JsonUtils.parse(modelTypeJ.toString(), List.class);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
