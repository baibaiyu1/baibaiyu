package com.gmd.faq.server.main.engine.rag;

import com.gmd.faq.server.main.engine.rag.store.VectorStore;
import com.gmd.faq.server.main.engine.rag.store.impl.NoOpVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置，确保始终有一个 VectorStore bean 可用
 *
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore noOpVectorStore() {
        return new NoOpVectorStore();
    }
}
