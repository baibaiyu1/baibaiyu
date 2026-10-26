package com.gmd.faq.server.main.node.nooperation;

import com.gmd.faq.server.main.common.consts.NodeConst;
import com.gmd.faq.server.main.common.enums.NodeType;
import com.gmd.faq.server.main.node.base.EnhancedNode;
import com.gmd.faq.server.main.node.base.NodeOutput;
import com.gmd.faq.server.main.node.base.context.NodeContext;
import com.gmd.faq.server.main.node.base.verify.VerifyResult;
import lombok.Getter;

import java.util.Map;

/**
 * 空操作节点，不执行任何操作，仅作为桥接节点使用
 *
 */
public class NoOperationNode extends EnhancedNode {

    @Getter
    private final Config config;

    public NoOperationNode(String id, String name, Config config) {
        super(id, name, NodeType.NO_OPERATION);
        this.config = config;
    }

    @Override
    protected NodeOutput doExecute(Map<String, Object> inputs, NodeOutput output, NodeContext context) {
        output.addOutput(NodeConst.DEFAULT_OUTPUT_NAME, true);
        output.markComplete();
        return output;
    }

    @Override
    public VerifyResult verifyConfig(Map<String, Object> inputs) {
        return VerifyResult.valid();
    }
}
