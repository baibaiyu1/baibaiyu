package com.gmd.faq.server.main.engine.knowledge;

import com.gmd.faq.server.main.common.entity.KnowledgeBaseConfig;
import com.gmd.faq.server.main.common.enums.KbType;
import io.agentscope.core.rag.Knowledge;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 描述：
 *
 **/
public interface IKnowledge extends SmartInitializingSingleton {
    Knowledge build(KnowledgeBaseConfig knowledgeBaseConfig);

    KbType type();

    @Override
    default void afterSingletonsInstantiated() {
        KnowledgeFactory.register(this);
    }
}
