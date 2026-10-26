package com.gmd.faq.server.main.node.list.filter;

import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.node.base.NodeOutput;
import com.gmd.faq.server.main.node.base.WorkflowUtils;
import com.gmd.faq.server.main.node.base.context.NodeContext;
import com.gmd.faq.server.main.node.base.inputout.OutputConfig;

import java.util.Objects;

/**
 * 描述：计算判断
 *
 **/
public class SimpleEvaluator {

    public static boolean evaluate(Object inputValue, Config config,  NodeContext context) {
        // 输入值是否为空
        if (FuncUtils.isEmpty(inputValue)) {
            return config.getItemIsNullUse();
        }

        // 验证类型是否支持运算符
        VariableTypeSupportSymbol.verifySupportSymbol(config.getSupportType(), config.getSimpleSymbol());

        // 比较值
        Object compareValue;
        CompareTo compareTo = config.getCompareTo();
        CompareTo.Type compareToType = compareTo.getType();
        if (compareToType == CompareTo.Type.CONSTANT) {
            // 推演出输入值类型
            OutputConfig.VariableType inputValueType = WorkflowUtils.inferType(inputValue);
            // 将比较值转换为输入值类型
            compareValue = WorkflowUtils.convertType(compareTo.getValue(), inputValueType);
        } else if (compareToType == CompareTo.Type.VARIABLE) {
            // 获取目标节点输出
            NodeOutput nodeOutput = context.getVariables().getNodeOutputs().get(compareTo.getSourceNodeId());
            if (nodeOutput == null) {
                throw new RuntimeException("找不到"+compareTo.getSourceNodeId()+"对应的节点输出");
            }
            // 获取变量值
            compareValue = nodeOutput.getOutput(compareTo.getValue().toString());
        } else {
            throw new RuntimeException("未知的比较类型");
        }

        return switch (config.getSimpleSymbol()) {
            case EQ -> NumberComparator.compare(inputValue, compareValue) == 0;
            case NE -> NumberComparator.compare(inputValue, compareValue) != 0;
            case GT -> NumberComparator.compare(inputValue, compareValue) > 0;
            case LT -> NumberComparator.compare(inputValue, compareValue) < 0;
            case GE -> NumberComparator.compare(inputValue, compareValue) >= 0;
            case LE -> NumberComparator.compare(inputValue, compareValue) <= 0;
            case CONTAINS -> ((String) inputValue).contains(compareValue.toString());
            case NOT_CONTAINS -> !((String) inputValue).contains(compareValue.toString());
            case STARTS_WITH -> ((String) inputValue).startsWith(compareValue.toString());
            case ENDS_WITH -> ((String) inputValue).endsWith(compareValue.toString());
            case EQUALS -> Objects.equals(inputValue, compareValue);
            case NOT_EQUALS -> !Objects.equals(inputValue, compareValue);
            case IS_TRUE -> (Boolean) inputValue;
            case IS_FALSE -> !(Boolean) inputValue;
        };
    }
}
