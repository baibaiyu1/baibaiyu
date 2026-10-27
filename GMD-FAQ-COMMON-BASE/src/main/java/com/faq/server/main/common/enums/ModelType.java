package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型类型
 *
 */
@Getter
@AllArgsConstructor
public enum ModelType {
    CHAT("文本"),
    EMBEDDING("向量"),
    RERANK("重排"),
    IMAGE("图像"),
    VIDEO("视频"),
    AUDIO("音频");

    private final String description;
}
