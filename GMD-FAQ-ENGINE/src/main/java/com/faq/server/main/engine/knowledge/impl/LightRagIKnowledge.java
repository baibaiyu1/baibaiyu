package com.gmd.faq.server.main.engine.knowledge.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.engine.knowledge.IKnowledge;
import com.gmd.faq.server.main.engine.knowledge.lightrag.LightRagKnowledge;
import io.agentscope.core.rag.Knowledge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将独立 LightRAG 实例接入统一知识库检索通路。 */
@Component
@RequiredArgsConstructor
public class LightRagIKnowledge implements IKnowledge {
    private final ObjectMapper objectMapper;

    @Override
    public Knowledge build(KnowledgeBaseConfig config) {
        return new LightRagKnowledge(config, objectMapper);
    }

    @Override
    public KbType type() {
        return KbType.LIGHTRAG;
    }
}
