package com.gmd.faq.server.main.engine.knowledge.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.engine.knowledge.IKnowledge;
import com.gmd.faq.server.main.engine.knowledge.lightrag.LightRagKnowledge;
import com.gmd.faq.server.main.knowledge.service.LightRagModelSyncService;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.knowledge.service.LightRagConnectionResolver;
import io.agentscope.core.rag.Knowledge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将独立 LightRAG 实例接入统一知识库检索通路。 */
@Component
@RequiredArgsConstructor
public class LightRagIKnowledge implements IKnowledge {
    private final ObjectMapper objectMapper;
    private final LightRagModelSyncService modelSync;
    private final KnowledgeBaseConfigService configs;
    private final LightRagConnectionResolver connections;

    @Override
    public Knowledge build(KnowledgeBaseConfig config) {
        var connection = connections.resolve(config);
        return new LightRagKnowledge(config, objectMapper, () -> {
            var current = configs.getById(config.getId());
            if (current == null || !Boolean.TRUE.equals(current.getEnabled()))
                throw new IllegalArgumentException("知识图谱已删除或停用");
            if (current.getKbType() != KbType.LIGHTRAG || !connection.equals(connections.resolve(current)))
                throw new IllegalArgumentException("知识图谱连接已变更，请刷新智能体后重新检索");
            modelSync.ensureReady(current);
        }, connection.json());
    }

    @Override
    public KbType type() {
        return KbType.LIGHTRAG;
    }
}
