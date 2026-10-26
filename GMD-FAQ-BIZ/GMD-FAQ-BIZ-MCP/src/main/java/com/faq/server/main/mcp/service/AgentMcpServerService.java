package com.gmd.faq.server.main.mcp.service;

import com.gmd.faq.server.main.common.entity.AgentMcpServer;
import com.gmd.faq.server.main.common.vo.AgentMcpBindingVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 智能体MCP服务器关联Service
 *
 */
public interface AgentMcpServerService extends IService<AgentMcpServer> {
    List<Long> getAgentIds(List<Long> mcpIds);

    List<Long> getMcpIds(Long agentDefinitionId);

    List<AgentMcpServer> listByAgentDefinitionId(Long agentDefinitionId);

    List<AgentMcpBindingVO> getBindings(Long agentDefinitionId);

    Boolean insertAgentMcpServer(Long agentDefinitionId, List<Long> mcpIds);

    Boolean deleteAgentMcpServer(List<Long> agentIds);

    Boolean saveAgentMcpServer(Long agentDefinitionId, List<Long> mcpIds, List<AgentMcpBindingVO> bindings);
}
