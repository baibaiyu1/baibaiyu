package com.gmd.faq.server.main.node.code;

import com.gmd.faq.server.main.node.base.CodeLanguage;
import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：配置配置项
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 执行代码源码
     */
    private String codeSource;
    /**
     * 语言
     */
    private CodeLanguage language;
}
