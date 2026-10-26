package com.gmd.faq.server.main.engine.hook.dynamices;

import com.gmd.faq.server.main.engine.InstanceLoader;
import io.agentscope.core.hook.Hook;

/**
 * 描述：HookInstanceLoader
 *
 **/
public interface HookInstanceLoader extends InstanceLoader<Hook> {
    /**
     * 初始化
     */
    @Override
    default void afterSingletonsInstantiated() {
        // 完成注册
        HookInstanceLoadFactory.registerLoader(this);
    };
}
