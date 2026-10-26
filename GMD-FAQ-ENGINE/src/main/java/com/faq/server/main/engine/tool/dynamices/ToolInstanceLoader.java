package com.gmd.faq.server.main.engine.tool.dynamices;

import com.gmd.faq.server.main.engine.InstanceLoader;

/**
 * 描述：实例加载接口
 *
 **/
public interface ToolInstanceLoader extends InstanceLoader<IDynamicAgentTool> {
    /**
     * 初始化
     */
    @Override
    default void afterSingletonsInstantiated() {
        // 完成注册
        ToolInstanceLoadFactory.registerLoader(this);
    };
}
