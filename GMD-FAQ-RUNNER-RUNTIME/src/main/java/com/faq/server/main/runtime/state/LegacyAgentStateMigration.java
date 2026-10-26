package com.gmd.faq.server.main.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonUtils;
import java.util.*;

/** 将 1.x 的上下文、暂停工具和计划转换为 2.0 状态，原消息 ID 保持稳定。 */
final class LegacyAgentStateMigration {
    private LegacyAgentStateMigration() {}

    static AgentState migrate(AgentState legacy, String userId, String sessionId, JsonNode plan) {
        List<Msg> context = new ArrayList<>(legacy.getContext());
        Set<String> resolved = new HashSet<>();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            message.getContentBlocks(ToolResultBlock.class).forEach(result -> resolved.add(result.getId()));
            if (message.getRole() != MsgRole.ASSISTANT) continue;
            if (message.getGenerateReason() == GenerateReason.REASONING_STOP_REQUESTED) {
                List<ContentBlock> blocks = new ArrayList<>();
                for (ContentBlock block : message.getContent()) {
                    if (block instanceof ToolUseBlock tool && !resolved.contains(tool.getId())) {
                        if ("create_plan".equals(tool.getName())) {
                            tool = new ToolUseBlock(tool.getId(), "todo_write", todoInput(tool.getInput()));
                        }
                        block = tool.withState(ToolCallState.ASKING);
                    }
                    blocks.add(block);
                }
                context.set(i, Msg.builder().id(message.getId()).name(message.getName()).role(message.getRole())
                        .content(blocks).metadata(message.getMetadata()).timestamp(message.getTimestamp()).build());
            }
            break;
        }
        TaskContextState tasks = new TaskContextState();
        if (plan != null && plan.path("subtasks").isArray()) {
            for (JsonNode subtask : plan.path("subtasks")) {
                String oldState = subtask.path("state").asText("TODO").toUpperCase(Locale.ROOT);
                Task.State status = switch (oldState) {
                    case "DONE", "COMPLETED", "ABANDONED" -> Task.State.COMPLETED;
                    case "IN_PROGRESS" -> Task.State.IN_PROGRESS;
                    default -> Task.State.PENDING;
                };
                tasks.tasksMutable().add(Task.builder().subject(subtask.path("name").asText("历史任务"))
                        .description(subtask.path("description").asText(""))
                        .state(status).metadata(Map.of("legacy_plan", plan.path("name").asText(""),
                                "expected_outcome", subtask.path("expectedOutcome").asText(""))).build());
            }
        }
        return AgentState.builder().userId(userId).sessionId(sessionId).context(context)
                .toolContext(legacy.getToolContext()).tasksContext(tasks).build();
    }

    private static Map<String, Object> todoInput(Map<String, Object> input) {
        JsonNode tree = JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(input), JsonNode.class);
        List<Map<String, String>> todos = new ArrayList<>();
        for (JsonNode subtask : tree.path("subtasks")) {
            todos.add(Map.of("content", subtask.path("name").asText("历史任务"), "status", "pending"));
        }
        return Map.of("todos", todos);
    }
}
