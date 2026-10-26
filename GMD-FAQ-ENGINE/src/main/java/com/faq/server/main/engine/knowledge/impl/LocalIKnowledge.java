package com.gmd.faq.server.main.engine.knowledge.impl;

import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import com.gmd.faq.server.main.engine.knowledge.IKnowledge;
import com.gmd.faq.server.main.engine.rag.knowledge.LocalKnowledge;
import com.gmd.faq.server.main.knowledge.service.KnowledgeBaseConfigService;
import com.gmd.faq.server.main.engine.rag.service.LocalRagService;
import io.agentscope.core.rag.Knowledge;
import org.springframework.stereotype.Component;

/**
 * 本地RAG知识库实现，集成到KnowledgeFactory
 *
 */
@Component
public class LocalIKnowledge implements IKnowledge {

    private final LocalRagService localRagService;
    private final KnowledgeBaseConfigService knowledgeBaseConfigService;

    public LocalIKnowledge(LocalRagService localRagService,
                           KnowledgeBaseConfigService knowledgeBaseConfigService) {
        this.localRagService = localRagService;
        this.knowledgeBaseConfigService = knowledgeBaseConfigService;
    }

    @Override
    public Knowledge build(KnowledgeBaseConfig knowledgeBaseConfig) {
        return new LocalKnowledge(
                knowledgeBaseConfig.getId(),
                localRagService,
                knowledgeBaseConfigService
        );
    }

    @Override
    public KbType type() {
        return KbType.LOCAL;
    }
}
