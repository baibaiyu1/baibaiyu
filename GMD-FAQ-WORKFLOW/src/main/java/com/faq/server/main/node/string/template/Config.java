package com.gmd.faq.server.main.node.string.template;

import com.gmd.faq.server.main.node.base.NodeConfig;
import com.gmd.faq.server.main.node.base.template.FormatterType;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：配置类
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    // 模版引擎只支持 STRING 和 VELOCITY
    private FormatterType templateType = FormatterType.STRING;
    private String template;
}
