package com.gmd.faq.server.main.engine.rag;

/**
 * 嵌入向量记录
 *
 */
public record EmbeddingRecord(Long id, Long chunkId, Long documentId,
                              Long knowledgeBaseConfigId, Long tenantId, float[] embedding) {
}
