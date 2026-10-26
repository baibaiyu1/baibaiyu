package com.gmd.faq.server.main.workflowbiz.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.node.base.EnhancedNode;
import com.gmd.faq.server.main.node.base.Node;
import com.gmd.faq.server.main.common.enums.NodeType;
import com.gmd.faq.server.main.node.base.inputout.InputConfig;
import com.gmd.faq.server.main.node.base.inputout.OutputConfig;
import com.gmd.faq.server.main.workflow.core.Edge;
import com.gmd.faq.server.main.workflow.run.RunWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class WorkflowDefinitionCompiler {
    private final ObjectMapper objectMapper;

    private static final Map<NodeType, NodeBinding> BINDINGS = new EnumMap<>(NodeType.class);

    static {
        bind(NodeType.START, com.gmd.faq.server.main.node.start.StartNode.class, com.gmd.faq.server.main.node.start.Config.class);
        bind(NodeType.END, com.gmd.faq.server.main.node.end.EndNode.class, com.gmd.faq.server.main.node.end.Config.class);
        bind(NodeType.NO_OPERATION, com.gmd.faq.server.main.node.nooperation.NoOperationNode.class, com.gmd.faq.server.main.node.nooperation.Config.class);
        bind(NodeType.CACHE_FETCH, com.gmd.faq.server.main.node.cache.fetch.CacheFetchNode.class, com.gmd.faq.server.main.node.cache.fetch.Config.class);
        bind(NodeType.CACHE_REFRESH, com.gmd.faq.server.main.node.cache.refresh.CacheRefreshNode.class, com.gmd.faq.server.main.node.cache.refresh.Config.class);
        bind(NodeType.CACHE_REMOVE, com.gmd.faq.server.main.node.cache.remove.CacheRemoveNode.class, com.gmd.faq.server.main.node.cache.remove.Config.class);
        bind(NodeType.CACHE_SET, com.gmd.faq.server.main.node.cache.set.CacheSetNode.class, com.gmd.faq.server.main.node.cache.set.Config.class);
        bind(NodeType.AGENT, com.gmd.faq.server.main.node.agent.AgentNode.class, com.gmd.faq.server.main.node.agent.Config.class);
        bind(NodeType.CODE, com.gmd.faq.server.main.node.code.CodeNode.class, com.gmd.faq.server.main.node.code.Config.class);
        bind(NodeType.DB_DELETE, com.gmd.faq.server.main.node.db.delete.DbDeleteNode.class, com.gmd.faq.server.main.node.db.delete.Config.class);
        bind(NodeType.DB_INSERT, com.gmd.faq.server.main.node.db.insert.DbInsertNode.class, com.gmd.faq.server.main.node.db.insert.Config.class);
        bind(NodeType.DB_SELECT, com.gmd.faq.server.main.node.db.select.DbSelectNode.class, com.gmd.faq.server.main.node.db.select.Config.class);
        bind(NodeType.DB_UPDATE, com.gmd.faq.server.main.node.db.update.DbUpdateNode.class, com.gmd.faq.server.main.node.db.update.Config.class);
        bind(NodeType.HTTP_EXTERNAL, com.gmd.faq.server.main.node.http.HttpExternalNode.class, com.gmd.faq.server.main.node.http.Config.class);
        bind(NodeType.IF_ELSE, com.gmd.faq.server.main.node.condition.ConditionNode.class, com.gmd.faq.server.main.node.condition.Config.class);
        bind(NodeType.ITERATE, com.gmd.faq.server.main.node.iterate.IterateNode.class, com.gmd.faq.server.main.node.iterate.Config.class);
        bind(NodeType.LIST_FILTER, com.gmd.faq.server.main.node.list.filter.ListFilterNode.class, com.gmd.faq.server.main.node.list.filter.Config.class);
        bind(NodeType.LIST_SORT, com.gmd.faq.server.main.node.list.sort.ListSortNode.class, com.gmd.faq.server.main.node.list.sort.Config.class);
        bind(NodeType.LOOP, com.gmd.faq.server.main.node.loop.LoopNode.class, com.gmd.faq.server.main.node.loop.Config.class);
        bind(NodeType.MATCH_RESULT, com.gmd.faq.server.main.node.match.result.MatchResultNode.class, com.gmd.faq.server.main.node.match.result.Config.class);
        bind(NodeType.MQ_PUSH, com.gmd.faq.server.main.node.mq.push.MqPushNode.class, com.gmd.faq.server.main.node.mq.push.Config.class);
        bind(NodeType.NON_EMPTY_SELECT, com.gmd.faq.server.main.node.none.empty.select.NonEmptySelectNode.class, com.gmd.faq.server.main.node.none.empty.select.Config.class);
        bind(NodeType.SERIALIZE, com.gmd.faq.server.main.node.serialize.SerializeNode.class, com.gmd.faq.server.main.node.serialize.Config.class);
        bind(NodeType.STRING_SPLIT, com.gmd.faq.server.main.node.string.split.StringSplitNode.class, com.gmd.faq.server.main.node.string.split.Config.class);
        bind(NodeType.STRING_TEMPLATE, com.gmd.faq.server.main.node.string.template.StringTemplateNode.class, com.gmd.faq.server.main.node.string.template.Config.class);
        bind(NodeType.UNSERIALIZE, com.gmd.faq.server.main.node.unserialize.UnserializeNode.class, com.gmd.faq.server.main.node.unserialize.Config.class);
        bind(NodeType.VARIABLE_AGG, com.gmd.faq.server.main.node.variable.agg.VariableAggNode.class, com.gmd.faq.server.main.node.variable.agg.Config.class);
        bind(NodeType.CONSTANT, com.gmd.faq.server.main.node.constant.ConstantNode.class, com.gmd.faq.server.main.node.constant.Config.class);
        bind(NodeType.TOOL_EXECUTE, com.gmd.faq.server.main.node.toolexecute.ToolExecuteNode.class, com.gmd.faq.server.main.node.toolexecute.Config.class);
        bind(NodeType.MCP_CALL, com.gmd.faq.server.main.node.mcp.McpNode.class, com.gmd.faq.server.main.node.mcp.Config.class);
        bind(NodeType.EMAIL_SEND, com.gmd.faq.server.main.node.channel.email.EmailSendNode.class, com.gmd.faq.server.main.node.channel.email.Config.class);
        bind(NodeType.WECOM_SEND, com.gmd.faq.server.main.node.channel.wecom.WecomSendNode.class, com.gmd.faq.server.main.node.channel.wecom.Config.class);
        bind(NodeType.DINGTALK_SEND, com.gmd.faq.server.main.node.channel.dingtalk.DingTalkSendNode.class, com.gmd.faq.server.main.node.channel.dingtalk.Config.class);
        bind(NodeType.FEISHU_SEND, com.gmd.faq.server.main.node.channel.feishu.FeishuSendNode.class, com.gmd.faq.server.main.node.channel.feishu.Config.class);
        bind(NodeType.INTENT_RECOGNITION, com.gmd.faq.server.main.node.intent.IntentRecognitionNode.class, com.gmd.faq.server.main.node.intent.Config.class);
        bind(NodeType.KNOWLEDGE_RETRIEVE, com.gmd.faq.server.main.node.knowledge.KnowledgeNode.class, com.gmd.faq.server.main.node.knowledge.Config.class);
    }

    public static Set<NodeType> supportedTypes() {
        return BINDINGS.keySet();
    }

    public RunWorkflow compile(String workflowId, Object definition) {
        JsonNode root = objectMapper.valueToTree(definition);
        RunWorkflow workflow = new RunWorkflow(workflowId);

        JsonNode nodes = root.path("nodes");
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("workflow.nodes must be an array");
        }
        for (JsonNode nodeJson : nodes) {
            workflow.addNode(compileNode(nodeJson));
        }

        JsonNode edges = root.path("edges");
        if (edges.isArray()) {
            for (JsonNode edgeJson : edges) {
                String id = text(edgeJson, "id", "edge-" + edgeJson.path("source").asText() + "-" + edgeJson.path("target").asText());
                workflow.addEdge(new Edge(id, text(edgeJson, "source", null), text(edgeJson, "target", null)));
            }
        }
        return workflow;
    }

    private Node compileNode(JsonNode nodeJson) {
        return compileSubNode(nodeJson);
    }

    /**
     * 公开的子节点编译方法，供 LoopSubWorkflowCompiler 等调用。
     */
    public Node compileSubNode(JsonNode nodeJson) {
        String id = text(nodeJson, "id", null);
        String name = text(nodeJson, "name", text(nodeJson, "label", id));
        NodeType type = NodeType.valueOf(text(nodeJson, "type", null));
        NodeBinding binding = BINDINGS.get(type);
        if (binding == null) {
            throw new IllegalArgumentException("Unsupported workflow node type: " + type);
        }

        Object config = objectMapper.convertValue(nodeJson.path("config"), binding.configClass());
        EnhancedNode node = instantiate(binding, id, name, config);

        JsonNode inputs = nodeJson.path("inputConfigs");
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                node.addInputConfig(compileInputConfig(input));
            }
        }

        JsonNode outputs = nodeJson.path("outputConfigs");
        if (outputs.isArray()) {
            for (JsonNode output : outputs) {
                OutputConfig outputConfig = objectMapper.convertValue(output, OutputConfig.class);
                if (outputConfig.getFromNodeId() == null) {
                    outputConfig.setFromNodeId(id);
                }
                node.addOutputConfig(outputConfig);
            }
        }

        return node;
    }

    private InputConfig compileInputConfig(JsonNode inputJson) {
        InputConfig config = new InputConfig();
        config.setName(text(inputJson, "name", "input"));

        String typeText = text(inputJson, "type", null);
        if (typeText != null && !typeText.isBlank()) {
            config.setType(OutputConfig.VariableType.valueOf(typeText));
        }

        String classifyText = text(inputJson, "classify", text(inputJson, "sourceType", null));
        if (classifyText == null || classifyText.isBlank()) {
            classifyText = InputConfig.InputClassify.NODE_OUTPUT.name();
        }
        config.setClassify(InputConfig.InputClassify.valueOf(classifyText));

        JsonNode valueNode = inputJson.get("value");
        if (valueNode != null && !valueNode.isNull()) {
            config.setValue(objectMapper.convertValue(valueNode, Object.class));
        }
        config.setVariableName(text(inputJson, "variableName", null));
        config.setSourceNodeId(text(inputJson, "sourceNodeId", text(inputJson, "nodeId", null)));
        config.setSourceOutputName(text(inputJson, "sourceOutputName", text(inputJson, "outputName", "output")));
        config.setExpression(text(inputJson, "expression", null));
        return config;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EnhancedNode instantiate(NodeBinding binding, String id, String name, Object config) {
        try {
            Constructor constructor = binding.nodeClass().getConstructor(String.class, String.class, binding.configClass());
            return (EnhancedNode) constructor.newInstance(id, name, config);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create workflow node " + id + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText();
    }

    private static void bind(NodeType type, Class<? extends EnhancedNode> nodeClass, Class<?> configClass) {
        BINDINGS.put(type, new NodeBinding(nodeClass, configClass));
    }

    private record NodeBinding(Class<? extends EnhancedNode> nodeClass, Class<?> configClass) {
    }
}
