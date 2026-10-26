package com.gmd.faq.server.main.node.unserialize;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：节点配置类
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    // 序列化格式
    private UnSerializeFormat format;
    // 是否排除空值
    private boolean excludeNulls;
    // 当format为BASE64/URL_ENCODED时需要设置编码格式
    private String encoding;
}
