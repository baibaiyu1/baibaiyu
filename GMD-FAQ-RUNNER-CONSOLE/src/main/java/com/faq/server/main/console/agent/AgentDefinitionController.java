package com.gmd.faq.server.main.console.agent;

import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.AgentChatKeyService;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.dto.AgentDefinitionDTO;
import com.gmd.faq.server.main.common.dto.AgentUiConfigDTO;
import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.r.ResultCode;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.AgentTagSupport;
import com.gmd.faq.server.main.common.vo.AgentDefinitionVO;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.studio.mapper.AgentStudioMapper;
import com.gmd.faq.server.main.longterm.mapper.AgentLongTermMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 智能体定义Controller
 *
 */
@RestController
@RequestMapping("/agent/definition")
@RequiredArgsConstructor
public class AgentDefinitionController {

    private final AgentDefinitionService agentDefinitionService;
    private final AgentChatKeyService agentChatKeyService;
    private final AgentStudioMapper agentStudioMapper;
    private final AgentLongTermMemoryMapper agentLongTermMemoryMapper;
    private final MessagePublisher messagePublisher;
    private final ResourceVisibilityService resourceVisibilityService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    @PermNeed({"agent:definition:list"})
    public R<IPage<AgentDefinitionVO>> page(PageParams pageParams, AgentDefinitionDTO query) {
        QueryWrapper<AgentDefinition> wrapper = MP.getQueryWrapper(query);
        if (query.getTag() != null && !query.getTag().isBlank()) {
            wrapper.apply("(JSON_CONTAINS(tags_json, JSON_QUOTE({0}))"
                    + " OR (tags_json IS NULL AND tag = {1}))", query.getTag(), query.getTag());
        }
        resourceVisibilityService.applyScope(wrapper, "AGENT", query.getScope(), query.getSpace());
        IPage<AgentDefinition> page = agentDefinitionService.page(MP.getPage(pageParams), wrapper);
        IPage<AgentDefinitionVO> pageVo = BeanUtils.copyPage(page, AgentDefinitionVO.class);
        for (int i = 0; i < page.getRecords().size(); i++) {
            pageVo.getRecords().get(i).setTags(AgentTagSupport.tagsOf(page.getRecords().get(i)));
        }
        List<AgentStudio> agentStudios = agentStudioMapper.selectList(null);
        List<AgentLongTermMemory> agentLongTermMemories = agentLongTermMemoryMapper.selectList(null);
        if (!agentStudios.isEmpty() || !agentLongTermMemories.isEmpty()) {
            Map<Long, Long> agentStudioMap = agentStudios.stream().collect(Collectors.toMap(
                    AgentStudio::getAgentDefinitionId,
                    AgentStudio::getStudioId,
                    (existing, replacement) -> existing));
            Map<Long, Long> agentLongTermMemoryMap = agentLongTermMemories.stream().collect(Collectors.toMap(
                    AgentLongTermMemory::getAgentDefinitionId,
                    AgentLongTermMemory::getLongTermMemoryConfigId,
                    (existing, replacement) -> existing));
            pageVo.getRecords().forEach(agentVo -> {
                if (agentStudioMap.containsKey(agentVo.getId())) {
                    agentVo.setStudioConfigId(agentStudioMap.get(agentVo.getId()));
                }
                if (agentLongTermMemoryMap.containsKey(agentVo.getId())) {
                    agentVo.setLongTermMemoryConfigId(agentLongTermMemoryMap.get(agentVo.getId()));
                }
            });
        }

        return R.data(pageVo);
    }

    /**
     * 详情
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{id}")
    public R<AgentDefinitionVO> detail(@PathVariable("id") Long id) {
        if (UserUtils.isChatKey()) {
            resourceVisibilityService.assertUsable("AGENT", id);
            AgentDefinition agent = agentDefinitionService.getById(id);
            if (agent == null) {
                return R.fail(ResultCode.NOT_FOUND, "智能体不存在");
            }
            AgentDefinitionVO guestView = new AgentDefinitionVO();
            guestView.setId(agent.getId());
            guestView.setName(agent.getName());
            guestView.setAgentCode(agent.getAgentCode());
            guestView.setDescription(agent.getDescription());
            guestView.setUiConfig(agent.getUiConfig());
            guestView.setEnableMemory(agent.getEnableMemory());
            guestView.setEnablePlanning(agent.getEnablePlanning());
            guestView.setShowToolProcess(agent.getShowToolProcess());
            guestView.setEnabled(agent.getEnabled());
            return R.data(guestView);
        }
        AgentDefinitionVO vo = agentDefinitionService.agentDefinitionDetail(id);
        if (vo == null) {
            return R.fail(ResultCode.NOT_FOUND, "智能体不存在");
        }
        vo.setUsed(agentDefinitionService.usedWithAgent(List.of(id)));

        return R.data(vo);
    }

    /**
     * 新增
     */
    @PostMapping
    @PermNeed({"agent:definition:add"})
    public R<AgentDefinitionVO> save(@RequestBody AgentDefinitionVO vo) {
        agentDefinitionService.saveAgentDefinition(vo);
        messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(vo.getId()));
        return R.data(vo);
    }

    /**
     * 修改
     */
    @PutMapping
    @PermNeed({"agent:definition:edit"})
    public R<Boolean> update(@RequestBody AgentDefinitionVO vo) {
        return R.data(agentDefinitionService.updateAgentDefinition(vo));
    }

    /**
     * 删除
     */
    @DeleteMapping
    @PermNeed({"agent:definition:remove"})
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(agentDefinitionService.deleteAgentDefinition(ids));
    }

    /**
     * 被哪些Agent使用
     */
    @PostMapping("used-with-agent")
    public R<List<Object>> usedWithAgent(@RequestBody List<Long> ids) {
        return R.data(agentDefinitionService.usedWithAgent(ids));
    }

    /**
     * 保存「功能增强 · Web App 用户体验」配置（开场白 / 推荐问题）
     *
     * <p>单独开一个轻量端点而不是走整体更新：该配置与智能体编排表单无关，
     * 且支持局部更新（只带其中一个字段时保留另一个）。
     */
    @PutMapping("/{id}/ui-config")
    @PermNeed({"agent:definition:edit"})
    public R<Boolean> saveUiConfig(@PathVariable("id") Long id, @RequestBody AgentUiConfigDTO body) {
        Boolean ok = agentDefinitionService.saveUiConfig(id, body.getOpeningMessage(), body.getOpeningQuestions());
        if (!Boolean.TRUE.equals(ok)) {
            return R.fail(ResultCode.NOT_FOUND, "智能体不存在");
        }
        return R.data(true);
    }

    @PutMapping("/{id}/external-share")
    @PermNeed({"agent:external:manage"})
    public R<Boolean> setExternalSharing(@PathVariable("id") Long id,
                                         @RequestBody ExternalShareRequest request) {
        agentChatKeyService.setExternalSharing(id, Boolean.TRUE.equals(request.enabled()));
        return R.data(true);
    }

    public record ExternalShareRequest(Boolean enabled) {
    }

    /**
     * 获取所有Tag
     */
    @GetMapping("/get/tags")
    public R<List<String>> listTags() {
        return R.data(agentDefinitionService.listTags());
    }

    @PostMapping("/tags")
    @PermNeed({"agent:definition:add", "agent:definition:edit"})
    public R<String> createTag(@RequestBody TagRequest request) {
        return R.data(agentDefinitionService.createTag(request.name()));
    }

    @DeleteMapping("/tags")
    @PermNeed({"agent:definition:add", "agent:definition:edit"})
    public R<Boolean> deleteTag(@RequestBody TagRequest request) {
        Boolean deleted = agentDefinitionService.deleteTag(request.name());
        if (!Boolean.TRUE.equals(deleted)) {
            return R.fail("标签不存在");
        }
        return R.data(true);
    }

    public record TagRequest(String name) {
    }

    @SkAccess
    @ChatKeyAccess
    @GetMapping("/{id}/allow/file-type")
    public R<List<String>> allowFileType(@PathVariable("id") Long id) {
        return R.data(agentDefinitionService.allowFileType(id));
    }

    /**
     * 获取Agent启用的工具
     */
    @SkAccess
    @GetMapping("/{agentId}/enabled/tools")
    public R<List<ToolConfig>> getEnabledToolsOfAgent(@PathVariable("agentId") Long agentId) {
        return R.data(agentDefinitionService.getEnabledToolsOfAgent(agentId));
    }

    /**
     * 获取Agent启用的技能包
     */
    @SkAccess
    @GetMapping("/{agentId}/enabled/skills")
    public R<List<SkillPackage>> getEnabledSkillsOfAgent(@PathVariable("agentId") Long agentId) {
        return R.data(agentDefinitionService.getEnabledSkillsOfAgent(agentId));
    }

    /**
     * 获取Agent启用的工作流
     */
    @SkAccess
    @GetMapping("/{agentId}/enabled/workflows")
    public R<List<Workflow>> getEnabledWorkflowsOfAgent(@PathVariable("agentId") Long agentId) {
        return R.data(agentDefinitionService.getEnabledWorkflowsOfAgent(agentId));
    }
}
