package com.gmd.faq.server.main.node.iterate;

import com.gmd.faq.server.main.node.base.CodeLanguage;
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
     * 迭代处理代码
     */
    private String iterateCode;
    /**
     * 代码语言
     */
    private CodeLanguage language;
}
