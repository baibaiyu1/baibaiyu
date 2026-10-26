package com.gmd.faq.server.main.hook.service;

import com.gmd.faq.server.main.common.entity.AgentHook;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 智能体Hook关联Service
 *
 */
public interface AgentHookService extends IService<AgentHook> {
    List<Long> getAgentIds(List<Long> hookIds);
    List<Long> getHookIds(Long agentDefinitionId);
    Boolean insertAgentHook(Long agentDefinitionId, List<Long> hookIds);
    Boolean deleteAgentHook(List<Long> agentIds);
    Boolean saveAgentHook(Long agentDefinitionId, List<Long> hookIds);
}
