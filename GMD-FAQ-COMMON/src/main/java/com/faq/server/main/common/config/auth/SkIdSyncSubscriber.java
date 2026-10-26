package com.gmd.faq.server.main.common.config.auth;

import com.gmd.faq.server.main.common.cluster.core.ChannelSubscriber;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.message.SkSyncMessage;
import com.gmd.faq.server.main.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 描述：SK ID同步消息监听器
 * 监听Redis频道接收其他节点的SK ID变更通知，更新本地内存
 *
 **/
@Slf4j
@Component
public class SkIdSyncSubscriber implements ChannelSubscriber {

    @Override
    public Topic getTopic() {
        return new ChannelTopic(RedisChannelTopic.SK_SYNC_CHANNEL);
    }

    @Override
    public void onMessage(String channel, String message) {
        if (!channel.equals(RedisChannelTopic.SK_SYNC_CHANNEL)) {
            return;
        }

        try {
            SkSyncMessage syncMessage = JsonUtils.parse(message, SkSyncMessage.class);

            if (syncMessage == null) {
                log.warn("接收到的SK同步消息解析失败");
                return;
            }

            // 跳过来自同一节点的消息，避免重复处理
            if (SysConst.CURRENT_NODE_ID.equals(syncMessage.getSourceNodeId())) {
                log.debug("跳过来自同一节点的SK同步消息 - node: {}", SysConst.CURRENT_NODE_ID);
                return;
            }

            List<Long> skIds = syncMessage.getSkIds();
            if (skIds == null || skIds.isEmpty()) {
                log.warn("接收到的SK同步消息中SK ID列表为空");
                return;
            }

            // 根据操作类型更新本地内存
            switch (syncMessage.getType()) {
                case ADD -> {
                    skIds.forEach(AuthInterceptor::addSkId);
                    log.debug("接收到SK ID添加消息，已更新本地内存 - skIds: {}, fromNode: {}",
                            skIds, syncMessage.getSourceNodeId());
                }
                case REMOVE -> {
                    AuthInterceptor.removeSkIds(skIds);
                    log.debug("接收到SK ID移除消息，已更新本地内存 - skIds: {}, fromNode: {}",
                            skIds, syncMessage.getSourceNodeId());
                }
                default -> log.warn("未知的SK同步消息类型: {}", syncMessage.getType());
            }
        } catch (Exception e) {
            log.error("处理SK同步消息失败", e);
        }
    }
}
