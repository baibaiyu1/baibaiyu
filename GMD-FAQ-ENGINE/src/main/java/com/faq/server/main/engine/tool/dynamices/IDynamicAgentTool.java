package com.gmd.faq.server.main.engine.tool.dynamices;

import com.gmd.faq.server.main.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.engine.agui.AgentContext;

import java.util.Map;

/**
 * 描述：动态代理工具接口
 *
 **/
public interface IDynamicAgentTool {
    /**
     * 执行
     */
    default Object execute(AgentContext agentContext, Map<String, Object> params) {
        return "抱歉，该工具尚未实现，暂不可用";
    }

    /**
     *  将对象转换为json字符串
     */
    default String toJsonString(Object obj) {
        return JsonUtils.toJsonStr(obj);
    }

    /**
     *  将字符串解析为json节点
     */
    default JsonNode parse(String string) {
        return JsonUtils.parse(string);
    }

    /**
     *  将字符串解析为指定类的实例
     */
    default <T> T parse(String string, Class<T> clazz) {
        return JsonUtils.parse(string, clazz);
    }
}
