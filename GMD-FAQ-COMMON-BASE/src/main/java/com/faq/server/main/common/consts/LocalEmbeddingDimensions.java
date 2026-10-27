package com.gmd.faq.server.main.common.consts;

import java.util.List;

/** 本地知识库各向量存储共同支持的维度；创建与清理必须使用同一集合。 */
public final class LocalEmbeddingDimensions {
    public static final List<Integer> SUPPORTED = List.of(64, 128, 256, 512, 768, 1024, 1536, 2048, 2560, 3072);

    private LocalEmbeddingDimensions() {}

    public static int[] values() {
        return SUPPORTED.stream().mapToInt(Integer::intValue).toArray();
    }
}
