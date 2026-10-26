package com.gmd.faq.server.main.node.mcp;

import com.gmd.faq.server.main.common.consts.NodeConst;
import com.gmd.faq.server.main.common.enums.NodeType;
import com.gmd.faq.server.main.node.base.EnhancedNode;
import com.gmd.faq.server.main.node.base.NodeOutput;
import com.gmd.faq.server.main.node.base.context.NodeContext;
import com.gmd.faq.server.main.node.base.spring.SpringContextHolder;
import com.gmd.faq.server.main.node.base.verify.VerifyFail;
import com.gmd.faq.server.main.node.base.verify.VerifyResult;
import lombok.Getter;

import java.util.Map;

/**
 * MCP调用节点。
 * 调用指定MCP服务上的工具，将输入绑定参数传递给工具并返回执行结果。
 *
 */
public class McpNode extends EnhancedNode {
    @Getter
    private final Config config;

    public McpNode(String id, String name, Config config) {
        super(id, name, NodeType.MCP_CALL);
        this.config = config;
    }

    @Override
    protected NodeOutput doExecute(Map<String, Object> inputs, NodeOutput output, NodeContext context) {
        try {
            return successNodeOutput(inputs, output);
        } catch (Exception e) {
            return executionNodeOutput(e, output);
        }
    }

    /**
     * 创建成功输出。
     */
    private NodeOutput successNodeOutput(Map<String, Object> inputs, NodeOutput output) {
        McpNodeExecutor executor = SpringContextHolder.getBean(McpNodeExecutor.class);
        Object result = executor.execute(config.getMcpToolId(), inputs);

        output.addExecutionContext("mcpServerId", config.getMcpServerId());
        output.addExecutionContext("mcpToolId", config.getMcpToolId());
        output.addOutput(NodeConst.DEFAULT_OUTPUT_NAME, result);
        output.markComplete();
        return output;
    }

    /**
     * 创建异常输出。
     */
    private NodeOutput executionNodeOutput(Exception e, NodeOutput output) {
        output.markFailed(getName() + "执行失败: " + e.getMessage());
        return output;
    }

    @Override
    public VerifyResult verifyConfig(Map<String, Object> inputs) {
        if (config.getMcpServerId() == null) {
            return VerifyResult.invalid(new VerifyFail("mcpServerId", "MCP服务ID不能为空"));
        }
        if (config.getMcpToolId() == null) {
            return VerifyResult.invalid(new VerifyFail("mcpToolId", "MCP工具ID不能为空"));
        }
        return VerifyResult.valid();
    }
}
