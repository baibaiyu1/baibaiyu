package com.gmd.faq.server.main.node.constant;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：常量节点配置类
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 求值引擎类型，默认GROOVY
     */
    private String evaluatorType = "GROOVY";

    /**
     * 计算表达式
     * 变量域 = 全局变量 + 输入绑定（同名时输入绑定优先）
     */
    private String expression;
}
