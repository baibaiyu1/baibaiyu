package com.gmd.faq.server.main.gateway.cluster;

import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 描述：网关集群同步消息发布器
 * 管理面变更提交事务后向所有网关节点广播，触发各节点刷新本地路由状态
 *
 **/
@Component
@RequiredArgsConstructor
public class GatewaySyncPublisher {
    private final MessagePublisher messagePublisher;

    /**
     * 广播同步消息（事务提交后发送）
     */
    public void publish(GatewaySyncType type, List<Long> ids) {
        GatewaySyncMessage message = new GatewaySyncMessage(type, ids);
        messagePublisher.publishAfterCommit(RedisChannelTopic.GATEWAY_SYNC_CHANNEL, JsonUtils.toJsonStr(message));
    }
}
