package com.gmd.faq.server.main.node.toolexecute;

import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具执行节点配置。
 *
 */
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 工具ID。
     */
    private Long toolId;
}
