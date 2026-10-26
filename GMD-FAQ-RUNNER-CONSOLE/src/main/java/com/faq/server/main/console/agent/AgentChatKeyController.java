package com.gmd.faq.server.main.console.agent;

import com.gmd.faq.server.main.agent.service.AgentChatKeyService;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.r.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 描述：智能体对话Key Controller
 *
 **/
@RestController
@RequestMapping("/agent/chat-key")
@RequiredArgsConstructor
public class AgentChatKeyController {
    private final AgentChatKeyService agentChatKeyService;

    /**
     * 获取chat key
     * @param agentId agent Id
     * @param refresh 是否刷新key
     */
    @GetMapping("/{agentId}")
    public R<String> getChatKey(@PathVariable("agentId") Long agentId,
                                @RequestParam("refresh") boolean refresh) {
        return R.data(agentChatKeyService.getChatKey(agentId, refresh));
    }

    @ChatKeyAccess
    @GetMapping("/{chatKey}/get-agent-id")
    public R<Long> getAgentIdByChatKey(@PathVariable("chatKey") String chatKey) {
        return R.data(agentChatKeyService.getAgentIdByChatKey(chatKey));
    }
}
