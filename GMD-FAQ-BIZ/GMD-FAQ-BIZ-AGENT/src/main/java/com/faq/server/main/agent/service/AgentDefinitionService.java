package com.gmd.faq.server.main.agent.service;

import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.SkillPackage;
import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.entity.Workflow;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 智能体定义Service
 *
 */
public interface AgentDefinitionService extends IService<AgentDefinition> {
    AgentDefinitionVO agentDefinitionDetail(Long id);
    Boolean saveAgentDefinition(AgentDefinitionVO agentDefinition);
    Boolean updateAgentDefinition(AgentDefinitionVO agentDefinition);
    Boolean deleteAgentDefinition(List<Long> ids);

    /**
     * 保存「功能增强 · Web App 用户体验」配置（开场白 / 推荐问题）
     *
     * <p>局部更新语义：入参为 {@code null} 的字段保持原值；推荐问题非空时按 6 条上限裁剪。
     *
     * @param id               智能体ID
     * @param openingMessage   开场白（可为 null 表示不改）
     * @param openingQuestions 推荐问题（可为 null 表示不改）
     * @return 智能体不存在时返回 false
     */
    Boolean saveUiConfig(Long id, String openingMessage, List<String> openingQuestions);

    /**
     * Fork 智能体：复制一份归当前账号所有的副本
     * <p>
     * 副本默认「仅创建者可见 + 停用」，并记录 Fork 血缘；派生数据（A2A 配置、定时任务、被引用情况）不复制。
     *
     * @param sourceId 源智能体ID
     * @param name     副本名称，为空时按「源名称 副本」生成
     * @return 副本详情
     */
    AgentDefinitionVO forkAgent(Long sourceId, String name);

    List<Object> usedWithAgent(List<Long> ids);
    /**
     * 获取所有Tag
     *
     * @return Tag列表
     */
    List<String> listTags();
    String createTag(String name);
    /**
     * 删除未被当前租户任何智能体引用的标签。
     *
     * @return 标签存在且已删除时返回 {@code true}，标签不存在时返回 {@code false}
     */
    Boolean deleteTag(String name);
    List<String> allowFileType(Long id);
    List<ToolConfig> getEnabledToolsOfAgent(Long agentId);
    List<SkillPackage> getEnabledSkillsOfAgent(Long agentId);
    List<Workflow> getEnabledWorkflowsOfAgent(Long agentId);
}
