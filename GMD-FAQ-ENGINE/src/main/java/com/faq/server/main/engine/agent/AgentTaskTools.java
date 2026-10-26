package com.gmd.faq.server.main.engine.agent;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.builtin.TodoTools;
import java.util.List;

/** 复用 2.0 任务状态，同时保留智能体配置的子任务上限。 */
public class AgentTaskTools extends TodoTools {
    private final int maxSubtasks;

    public AgentTaskTools(Integer maxSubtasks) {
        this.maxSubtasks = maxSubtasks == null ? 20 : Math.max(1, maxSubtasks);
    }

    @Override
    @Tool(name = "todo_write", description = "维护完整的任务列表，逐项跟踪 pending、in_progress、completed 状态。",
            stateInjected = true, readOnly = false, concurrencySafe = false)
    public String todoWrite(@ToolParam(name = "todos", description = "完整更新后的任务列表")
            List<TodoItem> todos, AgentState state) {
        if (todos != null && todos.size() > maxSubtasks) {
            return "Error: 子任务数量不能超过 " + maxSubtasks;
        }
        return super.todoWrite(todos, state);
    }
}
