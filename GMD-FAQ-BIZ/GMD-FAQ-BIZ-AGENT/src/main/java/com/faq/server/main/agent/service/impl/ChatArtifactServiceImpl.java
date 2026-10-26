package com.gmd.faq.server.main.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.agent.mapper.ChatArtifactMapper;
import com.gmd.faq.server.main.agent.service.ChatArtifactService;
import com.gmd.faq.server.main.common.entity.ChatArtifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 描述：对话产物 Service 实现
 *
 **/
@Slf4j
@Service
public class ChatArtifactServiceImpl
        extends ServiceImpl<ChatArtifactMapper, ChatArtifact>
        implements ChatArtifactService {

    @Override
    public void recordArtifact(ChatArtifact artifact) {
        if (artifact == null || artifact.getSessionId() == null || artifact.getFilePath() == null) {
            return;
        }
        if (artifact.getTenantId() == null) {
            // 没有租户归属就没法做隔离，直接丢弃而不是写脏数据
            log.warn("产物缺少 tenantId，已跳过: sessionId={}, path={}",
                    artifact.getSessionId(), artifact.getFilePath());
            return;
        }
        if (artifact.getCreateTime() == null) {
            artifact.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        }

        try {
            // 走忽略租户拦截器的 upsert：本方法在智能体执行线程上被调用，
            // 那里没有请求级租户上下文，交给拦截器会注入错误的 tenant_id。
            baseMapper.upsertIgnoreTenant(artifact);
        } catch (Exception e) {
            // 产物记录失败不应影响智能体主流程
            log.error("对话产物记录失败: sessionId={}, path={}, err={}",
                    artifact.getSessionId(), artifact.getFilePath(), e.getMessage());
        }
    }

    @Override
    public List<ChatArtifact> listBySession(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        // 读路径在控制台请求线程上，租户上下文可用，交给拦截器自动隔离
        return list(Wrappers.<ChatArtifact>lambdaQuery()
                .eq(ChatArtifact::getSessionId, sessionId)
                .orderByAsc(ChatArtifact::getCreateTime));
    }
}
