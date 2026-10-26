package com.gmd.faq.server.main.runtime.voice;

import com.gmd.faq.server.main.common.config.auth.PermNeed;
import com.gmd.faq.server.main.common.r.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 音频在 LiveKit 传输，所有业务请求仍通过浏览器当前登录身份鉴权。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("${agentscope.agui.path-prefix:/agui}/voice")
@PermNeed({"agent:chat:instruct"})
public class VoiceController {
    private final VoiceConfiguration config;
    private final VoiceRoomTokens tokens;
    private final VoiceConversationService conversation;

    public record Availability(boolean available) {}
    @GetMapping("/config")
    public R<Availability> config() { return R.data(new Availability(config.available())); }

    @PostMapping("/{threadId}/connect")
    public R<VoiceRoomTokens.Connection> connect(@PathVariable("threadId") String threadId) {
        conversation.requireOwnSession(threadId);
        return R.data(tokens.issue());
    }

    @PostMapping("/{threadId}/turn")
    public R<VoiceConversationService.Reply> turn(@PathVariable("threadId") String threadId,
                                                @RequestBody VoiceConversationService.Turn request) {
        return R.data(conversation.respond(threadId, request));
    }
}
