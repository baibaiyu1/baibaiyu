package com.gmd.faq.server.main.node.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.gmd.faq.server.main.node.base.NodeConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库检索节点配置。
 *
 */
@Getter
@Setter
public class Config implements NodeConfig {
    /**
     * 知识库配置ID。
     */
    private Long knowledgeBaseConfigId;
    /**
     * 核心检索配置覆盖项（key 与知识库 retrievalConfig 一致），为空时使用知识库默认配置。
     */
    private JsonNode retrievalConfig;
}
