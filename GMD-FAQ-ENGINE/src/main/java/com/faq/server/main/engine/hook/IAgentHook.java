package com.gmd.faq.server.main.engine.hook;

import com.gmd.faq.server.main.common.annotation.Scope;
import com.gmd.faq.server.main.common.enums.ScopeType;
import io.agentscope.core.hook.Hook;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 描述：代理钩子
 *
 **/
public interface IAgentHook extends Hook, SmartInitializingSingleton {
    default String getName() {
        return this.getClass().getSimpleName();
    }

    String getDescription();

    /**
     * 获取内置Hook的作用域配置
     * 未标注 @Scope 时默认返回 null，视为 GLOBAL（向后兼容）
     */
    default Scope getScope() {
        Class<?> clazz = this.getClass();
        if (clazz.isAnnotationPresent(Scope.class)) {
            return clazz.getAnnotation(Scope.class);
        }
        return null;
    }

    /**
     * 获取内置Hook的实际作用域类型
     * 未标注 @Scope 时默认为 GLOBAL
     */
    default ScopeType getScopeType() {
        Scope scope = getScope();
        return scope != null ? scope.value() : ScopeType.GLOBAL;
    }

    default void afterSingletonsInstantiated() {
        HooksRegister.register(this.getClass().getName(), this);
    };
}
