package com.gmd.faq.server.main.mcp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.dto.McpToolEnabledDTO;
import com.gmd.faq.server.main.common.dto.McpToolNeedConfirmDTO;
import com.gmd.faq.server.main.common.entity.AgentDefinition;
import com.gmd.faq.server.main.common.entity.AgentMcpServer;
import com.gmd.faq.server.main.common.entity.McpServer;
import com.gmd.faq.server.main.common.entity.McpTool;
import com.gmd.faq.server.main.common.enums.HealthStatus;
import com.gmd.faq.server.main.common.enums.McpActivationStatus;
import com.gmd.faq.server.main.common.enums.McpFailureSource;
import com.gmd.faq.server.main.common.enums.McpMode;
import com.gmd.faq.server.main.common.enums.McpProtocol;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.mcp.ToolSchemaRefreshResult;
import com.gmd.faq.server.main.mcp.service.ToolSchemaRefresher;
import com.gmd.faq.server.main.common.util.CryptoUtils;
import com.gmd.faq.server.main.common.util.ResourceOwnershipSupport;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.McpToolVO;
import com.gmd.faq.server.main.mcp.mapper.McpServerMapper;
import com.gmd.faq.server.main.mcp.service.AgentMcpServerService;
import com.gmd.faq.server.main.mcp.service.AgentMcpToolService;
import com.gmd.faq.server.main.mcp.service.McpRuntimeDegradeService;
import com.gmd.faq.server.main.mcp.service.McpServerService;
import com.gmd.faq.server.main.mcp.service.McpToolService;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * MCP 服务配置 Service 实现
 *
 */
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl extends ServiceImpl<McpServerMapper, McpServer> implements McpServerService {
    private static final String CONFIG_HASH_SALT = "MCP_CONFIG_HASH";
    private static final int DEFAULT_RUNTIME_FAIL_THRESHOLD = 3;

    private final JdbcTemplate jdbcTemplate;
    private final AgentMcpServerService agentMcpServerService;
    private final AgentMcpToolService agentMcpToolService;
    private final McpToolService mcpToolService;
    private final MessagePublisher messagePublisher;
    private final ToolSchemaRefresher toolSchemaRefresher;
    private final McpRuntimeDegradeService mcpRuntimeDegradeService;
    private final ObjectMapper objectMapper;
    private final ResourceVisibilityService resourceVisibilityService;

    @Override
    public List<Object> usedWithAgent(List<Long> ids) {
        List<Object> names = new ArrayList<>();
        List<Long> agentIds = agentMcpServerService.getAgentIds(ids);
        Set<String> visibleIds = resourceVisibilityService.visibilityMap("AGENT", agentIds).keySet();
        getAgentDefinitions(agentIds).forEach(agentDefinition -> {
            if (visibleIds.contains(agentDefinition.getId().toString())) {
                names.add(agentDefinition.getName());
            }
        });
        return names;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        // 资源级判权：列表能看到 ≠ 能删，归属人或管理员才能删除
        ids.forEach(id -> ResourceOwnershipSupport.assertManageable(requireServer(id), "删除"));
        List<Long> agentIds = agentMcpServerService.getAgentIds(ids);
        List<Long> mcpToolIds = mcpToolService.listByServerIds(ids)
                .stream()
                .map(McpTool::getId)
                .toList();
        agentMcpToolService.deleteByMcpToolIds(mcpToolIds);
        mcpToolService.deleteByMcpServerIds(ids);
        removeByIds(ids);
        boolean result = agentMcpServerService.remove(
                new LambdaQueryWrapper<AgentMcpServer>().in(AgentMcpServer::getMcpServerId, ids));
        publishAgentReregisterAfterCommit(agentIds);
        return result;
    }

    @Override
    public boolean save(McpServer entity) {
        // 归属人由服务端写入：客户端传 ownerId / visibility 一律不作数
        ResourceOwnershipSupport.initOwnership(entity);
        LocalDateTime now = LocalDateTime.now();
        entity.setActivationStatus(McpActivationStatus.NOT_ACTIVATED);
        entity.setActivationMessage("未连接");
        entity.setFailureSource(McpFailureSource.NONE);
        entity.setActivationStatusChangedAt(now);
        entity.setToolCount(0);
        entity.setActivationRevision(0L);
        entity.setNeedsSync(true);
        entity.setRuntimeFailThreshold(normalizeRuntimeFailThreshold(entity.getRuntimeFailThreshold()));
        entity.setConfigHash(buildConfigHash(
                entity.getProtocol(),
                entity.getMode(),
                entity.getTimeout(),
                entity.getProtocolConfig()));
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServer doUpdate(McpServer entity) {
        McpServer current = requireServer(entity.getId());
        ResourceOwnershipSupport.assertManageable(current, "修改");
        // 归属与可见性只能走「可见性」专用接口（同样只允许归属人/管理员），避免被 update 顺带篡改
        entity.setOwnerId(null);
        entity.setVisibility(null);
        String oldConfigHash = current.getConfigHash();
        if (oldConfigHash == null || oldConfigHash.isBlank()) {
            oldConfigHash = buildConfigHash(
                    current.getProtocol(),
                    current.getMode(),
                    current.getTimeout(),
                    current.getProtocolConfig());
        }

        if (entity.getRuntimeFailThreshold() != null) {
            entity.setRuntimeFailThreshold(normalizeRuntimeFailThreshold(entity.getRuntimeFailThreshold()));
        }

        String mergedConfigHash = buildConfigHash(
                firstNonNull(entity.getProtocol(), current.getProtocol()),
                firstNonNull(entity.getMode(), current.getMode()),
                firstNonNull(entity.getTimeout(), current.getTimeout()),
                firstNonNull(entity.getProtocolConfig(), current.getProtocolConfig()));
        entity.setConfigHash(mergedConfigHash);

        updateById(entity);

        McpServer updated = requireServer(entity.getId());
        boolean configChanged = !Objects.equals(oldConfigHash, mergedConfigHash);
        boolean shouldAutoActivate = current.getActivationStatus() == McpActivationStatus.ACTIVE
                && configChanged
                && Boolean.TRUE.equals(updated.getEnabled());

        if (shouldAutoActivate) {
            return activate(updated.getId());
        }

        publishAgentReregisterAfterCommit(agentMcpServerService.getAgentIds(List.of(updated.getId())));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServer activate(Long id) {
        return refreshServer(id, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServer syncTools(Long id) {
        return refreshServer(id, false);
    }

    @Override
    public List<McpToolVO> listTools(Long id) {
        McpServer server = requireServer(id);
        mcpToolService.ensureBackfilledFromCache(server);
        return mcpToolService.listToolVos(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServer updateToolGlobalEnabled(Long id, McpToolEnabledDTO dto) {
        McpServer server = requireServer(id);
        ResourceOwnershipSupport.assertManageable(server, "修改工具治理");
        ensureToolGovernanceWritable(server);
        List<Long> toolIds = dto == null || dto.getToolIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(dto.getToolIds()));
        if (!toolIds.isEmpty()) {
            mcpToolService.updateGlobalEnabled(id, toolIds, Boolean.TRUE.equals(dto.getEnabled()));
            publishAgentReregisterAfterCommit(agentMcpServerService.getAgentIds(List.of(id)));
        }
        return requireServer(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServer updateToolNeedConfirm(Long id, McpToolNeedConfirmDTO dto) {
        McpServer server = requireServer(id);
        ResourceOwnershipSupport.assertManageable(server, "修改工具治理");
        ensureToolGovernanceWritable(server);
        List<Long> toolIds = dto == null || dto.getToolIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(dto.getToolIds()));
        if (!toolIds.isEmpty()) {
            mcpToolService.updateNeedConfirm(id, toolIds, Boolean.TRUE.equals(dto.getNeedConfirm()));
            publishAgentReregisterAfterCommit(agentMcpServerService.getAgentIds(List.of(id)));
        }
        return requireServer(id);
    }

    private McpServer refreshServer(Long id, boolean markActivationTime) {
        McpServer current = requireServer(id);
        ResourceOwnershipSupport.assertManageable(current, "连接或同步");
        String configHash = buildConfigHash(
                current.getProtocol(),
                current.getMode(),
                current.getTimeout(),
                current.getProtocolConfig());
        String requestId = CryptoUtils.uuid();

        if (!beginActivation(current, requestId, configHash)) {
            return requireServer(id);
        }

        current = requireServer(id);
        ToolSchemaRefreshResult refreshResult = toolSchemaRefresher.refreshToolSchemas(current);
        LocalDateTime now = LocalDateTime.now();
        McpServer update = new McpServer();
        update.setId(id);
        update.setActivationMessage(refreshResult.getMessage());
        update.setFailureSource(McpFailureSource.NONE);
        update.setActivationStatusChangedAt(now);
        update.setLastToolSyncTime(now);
        update.setLastHealthCheck(now);
        update.setConfigHash(configHash);
        update.setNeedsSync(!refreshResult.isSuccess());

        if (markActivationTime) {
            update.setLastActivationTime(now);
        }

        if (refreshResult.isSuccess()) {
            update.setActivationStatus(McpActivationStatus.ACTIVE);
            update.setToolSchemas(refreshResult.getToolSchemas());
            update.setToolCount(refreshResult.getToolCount());
            update.setHealthStatus(HealthStatus.HEALTHY);
            update.setActivationRevision(nextRevision(current.getActivationRevision()));
        } else {
            update.setActivationStatus(McpActivationStatus.FAILED);
            update.setHealthStatus(HealthStatus.UNHEALTHY);
        }

        boolean applied = finishActivation(id, requestId, configHash, update);
        if (applied && refreshResult.isSuccess()) {
            McpServer refreshed = requireServer(id);
            mcpToolService.syncServerTools(refreshed, parseToolSchemas(refreshResult.getToolSchemas()));
            mcpRuntimeDegradeService.recordSuccess(
                    refreshed.getId(),
                    refreshed.getActivationRevision(),
                    refreshed.getConfigHash(),
                    refreshed.getRuntimeFailThreshold()
            );
        }
        if (applied) {
            publishAgentReregisterAfterCommit(agentMcpServerService.getAgentIds(List.of(id)));
        }
        return requireServer(id);
    }

    private boolean beginActivation(McpServer current, String requestId, String configHash) {
        McpServer update = new McpServer();
        update.setActivationStatus(McpActivationStatus.ACTIVATING);
        update.setActivationMessage("正在连接 MCP 并刷新工具目录");
        update.setFailureSource(McpFailureSource.NONE);
        update.setActivationStatusChangedAt(LocalDateTime.now());
        update.setActivationRequestId(requestId);
        update.setConfigHash(configHash);
        update.setNeedsSync(true);

        LambdaUpdateWrapper<McpServer> wrapper = new LambdaUpdateWrapper<McpServer>()
                .eq(McpServer::getId, current.getId());
        if (current.getActivationRequestId() == null) {
            wrapper.isNull(McpServer::getActivationRequestId);
        } else {
            wrapper.eq(McpServer::getActivationRequestId, current.getActivationRequestId());
        }

        return baseMapper.update(update, wrapper) > 0;
    }

    private boolean finishActivation(Long id, String requestId, String configHash, McpServer update) {
        LambdaUpdateWrapper<McpServer> wrapper = new LambdaUpdateWrapper<McpServer>()
                .eq(McpServer::getId, id)
                .eq(McpServer::getActivationRequestId, requestId)
                .eq(McpServer::getConfigHash, configHash);
        return baseMapper.update(update, wrapper) > 0;
    }

    private McpServer requireServer(Long id) {
        McpServer server = getById(id);
        if (server == null) {
            throw new RuntimeException("MCP 服务不存在");
        }
        return server;
    }

    private List<McpSchema.Tool> parseToolSchemas(String toolSchemas) {
        if (toolSchemas == null || toolSchemas.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(toolSchemas, new TypeReference<List<McpSchema.Tool>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("MCP 工具目录解析失败: " + e.getMessage(), e);
        }
    }

    private long nextRevision(Long currentRevision) {
        return currentRevision == null ? 1L : currentRevision + 1L;
    }

    private int normalizeRuntimeFailThreshold(Integer runtimeFailThreshold) {
        if (runtimeFailThreshold == null) {
            return DEFAULT_RUNTIME_FAIL_THRESHOLD;
        }
        return Math.max(runtimeFailThreshold, 0);
    }

    private String buildConfigHash(McpProtocol protocol,
                                   McpMode mode,
                                   Integer timeout,
                                   JsonNode protocolConfig) {
        String configJson = protocolConfig == null ? "" : protocolConfig.toString();
        String raw = (protocol == null ? "" : protocol.name())
                + "|"
                + (mode == null ? "" : mode.name())
                + "|"
                + (timeout == null ? "" : timeout)
                + "|"
                + configJson;
        return CryptoUtils.md5(raw, CONFIG_HASH_SALT);
    }

    private void ensureToolGovernanceWritable(McpServer server) {
        if (server.getActivationStatus() == McpActivationStatus.FAILED
                && server.getFailureSource() == McpFailureSource.RUNTIME_AUTO_DEGRADE) {
            throw new BusinessException("当前 MCP 处于运行时自动降级状态，仅可查看上次缓存，重新连接成功前不可修改工具治理");
        }
    }

    private <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private void publishAgentReregisterAfterCommit(List<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }

        Runnable publishAction = () -> agentIds.forEach(agentId ->
                messagePublisher.publishAfterCommit(RedisChannelTopic.AGENT_REREGISTER_CHANNEL, String.valueOf(agentId)));

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAction.run();
                }
            });
            return;
        }

        publishAction.run();
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
            agent.setId(rs.getLong("id"));
            agent.setName(rs.getString("name"));
            agent.setDescription(rs.getString("description"));
            return agent;
        });
    }
}
