package com.gmd.faq.server.main.engine.log;

import com.gmd.faq.server.main.common.entity.ChatMessage;
import com.gmd.faq.server.main.common.util.AgentMetadataStore;
import com.gmd.faq.server.main.common.util.BeanUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.subagent.SubAgentTraceEvent;
import com.gmd.faq.server.main.common.subagent.SubAgentTraceEventType;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：保存聊天记录hook
 *
 **/
public class ChatLogHook implements Hook {
    private final Map<String, Map<String, Object>> TOOL_CACHE_MAP = new ConcurrentHashMap<>();
    /**
     * 安全解析会话 ID
     *
     * <p>`threadId` 不保证是数字——智能体编排页的「预览与调试」用
     * `thread_&lt;ts&gt;_&lt;rand&gt;` 这种**不落库的临时 threadId**（见 `AguiRestController`），
     * 这类会话本就不该写聊天记录。以前这里直接 `Long.valueOf(threadId)`，
     * 会抛 `NumberFormatException: For input string: "thread_..."`，
     * 并被作为 ErrorEvent 推到前端（表现为「对话结束时弹出 For input string」）。
     *
     * @param threadId 线程/会话 ID
     * @return 数字会话 ID；非数字返回 null（调用方应跳过落库）
     */
    private Long parseSessionId(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return null;
        }
        if (!threadId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.valueOf(threadId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof ActingChunkEvent actingChunkEvent) {
            Object value = actingChunkEvent.getChunk().getMetadata().get(SubAgentTraceEvent.METADATA_KEY);
            if (value instanceof SubAgentTraceEvent traceEvent) {
                persistSubAgentTrace(event, traceEvent);
                return Mono.just(event);
            }
        }

        // 子代理已通过其父级的 ActingChunkEvent 进行持久化。若将其自身的 hook 回调作为普通聊天消息持久化，
        // 会导致卡片交错与数据重复。
        if (isSubAgentChild(event)) {
            return Mono.just(event);
        }

        boolean directResult = event instanceof PostCallEvent completed
                && Boolean.TRUE.equals(completed.getFinalMessage().getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT));
        if (event instanceof PostReasoningEvent || event instanceof PostActingEvent || event instanceof ErrorEvent || directResult) {
            // 解析租户ID和线程ID
            Long tenantId = extractTenantId(event);
            String threadId = extractThreadId(event);
            if (tenantId == null || threadId == null) {
                return Mono.just(event);
            }

            // 非数字的临时 threadId（编排页预览调试）没有可落库的会话，直接跳过记录
            Long sessionId = parseSessionId(threadId);
            if (sessionId == null) {
                return Mono.just(event);
            }

            // 处理错误事件
            if (event instanceof ErrorEvent errorEvent) {
                Throwable error = errorEvent.getError();
                ConcurrentLogProducer.pushLog(buildErrorMessage(
                        sessionId,
                        error.getMessage(),
                        tenantId));

                return Mono.just(event);
            }

            // 解析工具调用是否活跃
            boolean toolProcessActive = extractToolProcessActive(event);

            // 处理 reasoning 后置事件
            if (event instanceof PostReasoningEvent || directResult) {
                Msg reasoningMessage = event instanceof PostReasoningEvent reasoning
                        ? reasoning.getReasoningMessage() : ((PostCallEvent) event).getFinalMessage();
                List<ContentBlock> content = reasoningMessage.getContent();
                if (content.isEmpty()) {
                    return Mono.just(event);
                }

                for (ContentBlock block : content) {
                    if (block instanceof TextBlock textBlock) {
                        // 正文保存
                        String longTextContent = getLongTextContent(textBlock);
                        if (longTextContent != null) {
                            ConcurrentLogProducer.pushLog(buildChatMessage(
                                    sessionId,
                                    "assistant",
                                    longTextContent,
                                    tenantId));
                        }
                    } else if (block instanceof ThinkingBlock thinkingBlock) {
                        if (Boolean.FALSE.equals(AgentMetadataStore.get(event.getAgent().getAgentId(), "showThinking"))) continue;
                        // 思考保存
                        String longThinkingContent = getLongThinkingContent(thinkingBlock);
                        if (longThinkingContent != null) {
                            ConcurrentLogProducer.pushLog(buildChatMessage(
                                    sessionId,
                                    "thinking",
                                    longThinkingContent,
                                    tenantId));
                        }
                    } else if (block instanceof ToolUseBlock toolUseBlock) {
                        String longToolUseContent = getLongToolUseContent(toolUseBlock);
                        if (longToolUseContent != null) {
                            String toolCallId = toolUseBlock.getId();
                            if (toolCallId == null) {
                                toolCallId = UUID.randomUUID().toString();
                            }
                            // 工具调用入参暂保存
                            TOOL_CACHE_MAP.put(toolCallId, Map.of(
                                    "tool_name", toolUseBlock.getName(),
                                    "tool_args", longToolUseContent,
                                    "tool_start", System.currentTimeMillis()
                            ));
                        }

                    }
                }
            }
            // 处理 acting 后置事件
            else if (event instanceof PostActingEvent postActingEvent) {
                Msg reasoningMessage = postActingEvent.getToolResultMsg();
                List<ContentBlock> content = reasoningMessage.getContent();
                if (content.isEmpty()) {
                    return Mono.just(event);
                }

                // 工具结果保存
                for (ContentBlock block : content) {
                    if (block instanceof ToolResultBlock toolResult) {
                        String toolId = toolResult.getId();
                        if (toolResult.getMetadata().containsKey(SubAgentTraceEvent.FINAL_RESULT_METADATA_KEY)) {
                            Object invocationId = toolResult.getMetadata()
                                    .get(SubAgentTraceEvent.FINAL_RESULT_METADATA_KEY);
                            TOOL_CACHE_MAP.remove(toolId == null ? String.valueOf(invocationId) : toolId);
                            continue;
                        }
                        try {
                            Map<String, Object> toolCache = TOOL_CACHE_MAP.get(toolId);
                            ToolResultPresentation presentation = ToolResultPresentation.from(toolResult);
                            if (!toolProcessActive && !presentation.hasAttachments()) continue;
                            String toolName = toolCache != null ? toolCache.get("tool_name").toString() : toolResult.getName();
                            String args = toolCache != null ? toolCache.get("tool_args").toString() : "";
                            long elapsed = toolCache != null ? System.currentTimeMillis() - (Long) toolCache.get("tool_start") : 0;
                            ConcurrentLogProducer.pushLog(buildChatMessage(
                                    sessionId,
                                    "tool",
                                    buildToolContent(
                                            toolResult.getId(), toolName, elapsed, args, presentation, toolProcessActive),
                                    tenantId));
                        } finally {
                            TOOL_CACHE_MAP.remove(toolId);
                        }
                    }
                }
            }
        }

        return Mono.just(event);
    }

    private void persistSubAgentTrace(HookEvent event, SubAgentTraceEvent traceEvent) {
        Long tenantId = extractTenantId(event);
        String threadId = extractThreadId(event);
        if (tenantId == null || threadId == null) {
            return;
        }
        Long sessionId = parseSessionId(threadId);
        if (sessionId == null) {
            return;
        }

        BeanUtils.getBean(SubAgentTracePersistenceService.class).record(tenantId, sessionId, traceEvent);

        // 锚点参与正常的聊天分支路径；详细信息保留在专用的追踪表中，
        // 并在读取历史记录时批量加载。
        if (traceEvent.getEventType() == SubAgentTraceEventType.STARTED) {
            Map<String, Object> anchor = new HashMap<>();
            anchor.put("invocationId", traceEvent.getInvocationId());
            if (traceEvent.getAgent() != null) {
                anchor.put("agentTitle", traceEvent.getAgent().getTitle());
                anchor.put("agentCode", traceEvent.getAgent().getCode());
            }
            ConcurrentLogProducer.pushLog(buildChatMessage(
                    sessionId, "subagent", JsonUtils.toJsonStr(anchor), tenantId));
        }
    }

    private boolean isSubAgentChild(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "subagentInvocationId") != null;
        }
        return false;
    }

    private Long extractTenantId(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "tenantId");
        }
        return null;
    }
    private String extractThreadId(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            return AgentMetadataStore.get(agentBase.getAgentId(), "threadId");
        }
        return null;
    }
    private boolean extractToolProcessActive(HookEvent event) {
        if (event.getAgent() instanceof AgentBase agentBase) {
            Object o = AgentMetadataStore.get(agentBase.getAgentId(), "toolProcessActive");
            if (o instanceof Boolean b) return b;
        }
        return false;
    }

    private String getLongTextContent(TextBlock textBlock) {
        String text = textBlock.getText();
        if (text.isEmpty()) {
            return null;
        }
        return text;
    }

    // 获取思考内容
    private String getLongThinkingContent(ThinkingBlock thinkingBlock) {
        String thinking = thinkingBlock.getThinking();
        if (thinking.isEmpty()) {
            return null;
        }
        return thinking;
    }


    // 获取中文工具使用内容
    private String getLongToolUseContent(ToolUseBlock toolUseBlock) {
        String content = toolUseBlock.getContent();
        if (content.isEmpty()) {
            return null;
        }

        return content;
    }

    // 构建工具内容
    private String buildToolContent(String toolCallId, String toolName, Long totalTimes, String args,
                                    ToolResultPresentation result, boolean showDetails) {
        Map<String, Object> toolContent = new HashMap<>() {{
            put("toolCallId", toolCallId);
            put("name", toolName);
            put("totalTimes", totalTimes);
            put("args", showDetails ? args : "");
            put("result", result.text());
            put("images", result.images());
            put("artifacts", result.artifacts());
            put("citations", result.citations());
            put("hideDetails", !showDetails);
        }};
        return JsonUtils.toJsonStr(toolContent);
    }

    // 构建聊天消息
    private ChatMessage buildChatMessage(Long sessionId, String role, String content, Long tenantId) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setSessionId(sessionId);
        chatMessage.setRole(role);
        chatMessage.setContent(content);
        chatMessage.setTenantId(tenantId);

        return chatMessage;
    }

    // 构建错误消息
    private ChatMessage buildErrorMessage(Long sessionId, String content, Long tenantId) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setSessionId(sessionId);
        chatMessage.setRole("error");
        chatMessage.setContent(content);
        chatMessage.setTenantId(tenantId);

        return chatMessage;
    }
}
