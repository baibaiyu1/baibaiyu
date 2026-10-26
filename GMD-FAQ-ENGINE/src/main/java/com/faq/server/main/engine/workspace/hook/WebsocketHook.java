package com.gmd.faq.server.main.engine.workspace.hook;

import com.gmd.faq.server.main.common.enums.WsMessageType;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.ws.WebSocketClient;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 描述：工作空间websocket钩子
 * 目前这个类做的比较简单，但是路子是通的，后续可以扩展，精确到每一个文件。
 * 当然需要前端进行配合，接收并处理这些websocket消息
 *
 **/
@Component
@RequiredArgsConstructor
public class WebsocketHook implements Hook {
    private final WebSocketClient webSocketClient;

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActingEvent) {
            String threadId = extractThreadId(event);
            if (threadId == null) {
                return Mono.just(event);
            }

            ToolUseBlock toolUse = postActingEvent.getToolUse();
            if (toolUse != null && ToolConstants.PATH_SENSITIVE_TOOLS.contains(toolUse.getName())) {
                sendWebsocketToWorkspace(threadId, toolUse);
            }
        }

        return Mono.just(event);
    }

    private void sendWebsocketToWorkspace(String threadId, ToolUseBlock toolUse) {
        String name = toolUse.getName();

        switch (name) {
            case "insert_text_file":
            case "write_text_file":
                Map<String, Object> input = toolUse.getInput();
                // file_path 可能缺失或为 null：模型漏传 / 传了 path 之类的别名都会走到这里，
                // 直接 .toString() 会抛 NPE（Map.get 返回 null），把整轮对话打断。
                Object filePath = input == null ? null : input.get("file_path");
                sendFileChangeWs(threadId, filePath == null ? null : filePath.toString());
                break;
            case "execute_shell_command":
                sendFileChangeWs(threadId, null);
                break;
            default:
                break;
        }
    }

    public void sendFileChangeWs(String threadId, String fileName) {
        webSocketClient.broadcastCluster(
                WsMessageType.WORKSPACE_FILE_CHANGE.name(),
                new HashMap<String, Object>() {{
                    put("fileName", fileName);
                    put("sessionId", threadId);
                }});
    }

    private String extractThreadId(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "threadId");
        }
        return null;
    }
}
