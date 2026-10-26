package com.gmd.faq.server.main.node.cache.set;

import com.gmd.faq.server.main.node.base.NodeConfig;
import com.gmd.faq.server.main.node.base.template.FormatterType;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：缓存设置节点配置
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {

    /**
     * 缓存ID，对应 GMD-FAQ-BIZ-CACHE 中配置的缓存实例
     */
    private String cacheId;

    /**
     * 缓存键，支持 Velocity 动态变量语法
     */
    private String key;

    /**
     * 缓存值（支持 Velocity 模板的动态 JSON 对象、字符串、数字等）
     */
    private Object value;

    /**
     * 过期时间（秒），null 或 0 表示永不过期
     */
    private Long expire;

    /**
     * 模板格式化器类型，默认 VELOCITY
     */
    private FormatterType formatterType = FormatterType.VELOCITY;
}
