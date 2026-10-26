package com.gmd.faq.server.main.engine.rag;

/**
 * 向量检索结果
 *
 */
public record RetrievalResult(Long chunkId, Long documentId, double score) {
}
