package com.gmd.faq.server.main.console.workspace;

import com.gmd.faq.server.main.common.cluster.core.ChannelSubscriber;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

/** 删除会话后清理 console 持有的工作空间快照。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceDeleteSubscriber implements ChannelSubscriber {

    private final WorkspaceSyncInternalController snapshots;

    @Override
    public Topic getTopic() {
        return new ChannelTopic(RedisChannelTopic.WORKSPACE_DELETE_CHANNEL);
    }

    @Override
    public void onMessage(String channel, String message) {
        if (!RedisChannelTopic.WORKSPACE_DELETE_CHANNEL.equals(channel)) {
            return;
        }
        try {
            WorkspaceDeleteMessage payload = JsonUtils.parse(message, WorkspaceDeleteMessage.class);
            if (payload != null) {
                snapshots.deleteSnapshot(payload.tenantCode(), payload.sessionId());
            }
        } catch (Exception e) {
            log.warn("删除工作空间快照失败: {}", e.getMessage(), e);
        }
    }

    public record WorkspaceDeleteMessage(String tenantCode, String sessionId) {
    }
}
