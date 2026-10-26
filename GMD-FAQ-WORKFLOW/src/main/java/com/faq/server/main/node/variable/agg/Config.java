package com.gmd.faq.server.main.node.variable.agg;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：配置类
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 选择策略
     */
    private Strategy strategy = Strategy.MAP;

    /**
     * 排除空值
     */
    private boolean excludeNull = false;

    /**
     * 拼接符号
     * 如果strategy为STRING，那么需要指定拼接符号，默认""
     */
    private String splicingSymbol = "";

    public enum Strategy {
        ARRAY, // 聚合成array
        MAP, // 聚合成功map
        STRING // 聚合成功string
    }
}
