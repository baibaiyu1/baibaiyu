package com.gmd.faq.server.main.engine.agui;

import com.gmd.faq.server.main.common.cluster.core.ChannelSubscriber;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;

/**
 * 描述：Redis 消息订阅者 - 仅处理 gmd-faq:agent:cluster:reRegister 频道的跨节点消息
 *
 **/
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "runtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentReRegisterMessageSubscriber implements ChannelSubscriber {

    private final AguiAgentConfiguration aguiAgentConfiguration;

    @Override
    public Topic getTopic() {
        return new ChannelTopic(RedisChannelTopic.AGENT_REREGISTER_CHANNEL);
    }

    @Override
    public void onMessage(String channel, String message) {
        if (!channel.equals(RedisChannelTopic.AGENT_REREGISTER_CHANNEL)) {
            return;
        }

        aguiAgentConfiguration.reRegisterAgent(Long.parseLong(message));
    }
}
