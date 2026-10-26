package com.gmd.faq.server.main.node.end;

import com.gmd.faq.server.main.node.base.NodeConfig;
import com.gmd.faq.server.main.node.base.template.FormatterType;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：配置信息
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 响应模板
     */
    private String responseTemplate;
    /**
     * 响应模板格式化类型
     */
    private FormatterType formatterType = FormatterType.JACKSON;
}
