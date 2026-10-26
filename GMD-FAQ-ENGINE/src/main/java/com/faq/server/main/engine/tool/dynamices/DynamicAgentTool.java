package com.gmd.faq.server.main.engine.tool.dynamices;

import com.gmd.faq.server.main.common.entity.ToolConfig;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 描述：动态工具构建类
 *
 **/
public class DynamicAgentTool implements AgentTool {
    private final ToolConfig toolConfig;

    public DynamicAgentTool(ToolConfig toolConfig) {
        this.toolConfig = toolConfig;
    }

    @Override
    public String getName() {
        return toolConfig.getToolId();
    }

    @Override
    public String getDescription() {
        return toolConfig.getDescription();
    }

    @Override public boolean isReturnDirect() { return Boolean.TRUE.equals(toolConfig.getReturnDirect()); }
    @Override public Map<String, Object> getOutputSchema() {
        JsonNode schema = toolConfig.getOutputSchema();
        return schema != null && schema.isObject() ? JsonUtils.parse(schema.toString(), Map.class) : null;
    }

    @Override
    public Map<String, Object> getParameters() {
        // 获取输入参数的 JSON Schema
        JsonNode inputSchema = toolConfig.getInputSchema();
        if (inputSchema == null || inputSchema.isNull()) {
            return Map.of();
        }

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        // 构建 properties 和 required
        for (JsonNode jsonNode : inputSchema) {
            String name = JsonUtils.getStringValue(jsonNode, "name", true);
            String type = JsonUtils.getStringValue(jsonNode, "type", true);
            String description = JsonUtils.getStringValue(jsonNode, "description", false);
            String defaultValue = JsonUtils.getStringValue(jsonNode, "defaultValue", false);
            boolean required_ = JsonUtils.getBooleanValue(jsonNode, "required", false);

            properties.put(name, new HashMap<>(){{
                put("type", type);

                if (!FuncUtils.isEmpty(description)) {
                    put("description", description);
                } else {
                    put("description", name);
                }
                if (!FuncUtils.isEmpty(defaultValue)) {
                    put("defaultValue", defaultValue);
                }
            }});

            if (required_) {
                required.add(name);
            }
        }

        return new HashMap<>() {{
            put("type", "object");
            put("properties", properties);
            put("required", required);
        }};
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                // 拿到动态工具的实例
                IDynamicAgentTool dynamicAgentTool = ToolInstanceLoadFactory
                        .getInstanceLoader(toolConfig.getLanguage()).loadInstance(toolConfig.getCode());

                Map<String, Object> params = getParams(param);

                // 获取 AgentContext
                AgentContext agentContext = param.getContext().get(AgentContext.class);
                Object result = dynamicAgentTool.execute(agentContext, params);

                if (result instanceof ToolResultBlock nativeResult) {
                    return nativeResult.withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
                }
                if (result instanceof io.agentscope.core.message.ContentBlock block) {
                    return ToolResultBlock.of(param.getToolUseBlock().getId(), param.getToolUseBlock().getName(), block);
                }

                return ToolResultBlock.of(
                        param.getToolUseBlock().getId(),
                        param.getToolUseBlock().getName(),
                        TextBlock.builder().text(result instanceof String text ? text : JsonUtils.toJsonStr(result)).build()
                );
            } catch (Exception e) {
                return ToolResultBlock.error(e.getMessage() == null ? "工具执行失败" : e.getMessage())
                        .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
            }
        });
    }

    /**
     * 获取工具执行参数（Map 形式，按参数名取值）
     *
     * @param param 工具调用参数
     * @return 工具执行参数 Map
     */
    private Map<String, Object> getParams(ToolCallParam param) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> input = param.getInput();
        JsonNode inputSchema = toolConfig.getInputSchema();

        if (inputSchema != null && !inputSchema.isEmpty()) {
            inputSchema.forEach(jsonNode -> {
                if (jsonNode.has("name") && !jsonNode.get("name").asText().trim().isEmpty()) {
                    String name = jsonNode.get("name").textValue();

                    // 如果请求参数中包含该参数，则使用请求中的值
                    if (input != null && input.containsKey(name)) {
                        params.put(name, input.get(name));
                    } else if (JsonUtils.getBooleanValue(jsonNode, "required", false)) {
                        // 如果该参数被定义为必传字段，但请求参数中不包含该参数，则使用默认值
                        String defaultValue = JsonUtils.getStringValue(jsonNode, "defaultValue", false);
                        params.put(name, defaultValue);
                    }
                }
            });
        }

        return params;
    }
}
