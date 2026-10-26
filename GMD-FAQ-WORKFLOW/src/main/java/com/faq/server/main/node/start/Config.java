package com.gmd.faq.server.main.node.start;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 描述：配置类
 *
 **/
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 请求参数
     */
    private List<Param> params = List.of();
}
