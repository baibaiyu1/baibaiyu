package com.gmd.faq.server.main.node.iterate;

import com.gmd.faq.server.main.common.consts.NodeConst;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.node.base.EnhancedNode;
import com.gmd.faq.server.main.node.base.NodeOutput;
import com.gmd.faq.server.main.common.enums.NodeType;
import com.gmd.faq.server.main.node.base.context.NodeContext;
import com.gmd.faq.server.main.node.base.feature.LoopableNode;
import com.gmd.faq.server.main.node.base.security.SecurityCheckResult;
import com.gmd.faq.server.main.node.base.security.SecurityChecker;
import com.gmd.faq.server.main.node.base.security.SecurityCheckerFactory;
import com.gmd.faq.server.main.node.base.verify.VerifyFail;
import com.gmd.faq.server.main.node.base.verify.VerifyResult;
import com.gmd.faq.server.main.node.iterate.load.InstanceLoadFactory;
import lombok.Getter;

import java.util.*;

/**
 * 描述：迭代节点
 *
 **/
public class IterateNode extends EnhancedNode implements LoopableNode {
    @Getter
    private Config config;
    public IterateNode(String id, String name, Config config) {
        super(id, name, NodeType.ITERATE);
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
     * 创建成功输出
     * @param inputs 节点输入
     * @param output 节点输出
     * @return 节点输出
     */
    private NodeOutput successNodeOutput(Map<String, Object> inputs, NodeOutput output) throws Exception {
        // 获取代码执行对象
        IteratorExecutor iteratorExecutor = InstanceLoadFactory.getInstanceLoader(config.getLanguage())
                .loadInstance(config.getIterateCode());
        if (iteratorExecutor == null) {
            throw new Exception("无法加载迭代器代码执行对象");
        }

        Object input = inputs.get(NodeConst.DEFAULT_INPUT_NAME);
        // 迭代每一个元素，并将其处理并添加到输出结果中
        processCollectionInput(input, iteratorExecutor, output);

        output.markComplete();
        return output;
    }

    /**
     * 处理集合输入
     * @param input 输入
     * @param iteratorExecutor 迭代器代码执行对象
     * @param output 输出
     */
    private void processCollectionInput(Object input, IteratorExecutor iteratorExecutor, NodeOutput output) throws Exception {
        Collection<?> inputCollection = (Collection<?>) input;
        Iterator<?> iterator = inputCollection.iterator();
        List<Object> outputList = new LinkedList<>();

        int index = 0;
        while (iterator.hasNext()) {
            Object item = iterator.next();
            try {
                // 执行迭代器代码处理每个元素，获取处理结果
                Object result = iteratorExecutor.doIterate(item, index);
                outputList.add(result);
            } catch (Exception e) {
                throw new Exception("处理元素时发生错误: " + e.getMessage(), e);
            } finally {
                index++;
            }
        }

        output.addOutput(NodeConst.DEFAULT_OUTPUT_NAME, outputList);
    }

    /**
     * 异常节点输出
     */
    private NodeOutput executionNodeOutput(Exception e, NodeOutput output) {
        output.markFailed( getName() + "执行失败: " + e.getMessage());
        return output;
    }

    @Override
    public VerifyResult verifyConfig(Map<String, Object> inputs) {
        if (inputs.get("is_debug_verify") == null) {
            Object input = inputs.get(NodeConst.DEFAULT_INPUT_NAME);
            if (!(input instanceof Iterable<?>)) {
                return VerifyResult.invalid(new VerifyFail("input", "输入必须是一个迭代器"));
            }
        }

        // 代码语言
        if (FuncUtils.isEmpty(config.getLanguage())) {
            return VerifyResult.invalid(new VerifyFail("language", "代码语言不能为空"));
        }

        // 代码源
        if (FuncUtils.isEmpty(config.getIterateCode())) {
            return VerifyResult.invalid(new VerifyFail("iterateCode", "代码源不能为空"));
        }

        // 代码安全检查
        SecurityChecker checker = SecurityCheckerFactory.getChecker(config.getLanguage());
        if (checker != null) {
            SecurityCheckResult checkResult = checker.checkCodeSecurity(config.getIterateCode());
            if (!checkResult.isSafe()) {
                VerifyResult invalid = VerifyResult.invalid();
                checkResult.errors().forEach(error -> invalid.addError("[error]iterateCode", error));
                checkResult.warnings().forEach(warning -> invalid.addError("[warning]iterateCode", warning));
                return invalid;
            }
        } else {
            return VerifyResult.invalid(new VerifyFail("language", "不支持的代码语言"));
        }

        return VerifyResult.valid();
    }
}
