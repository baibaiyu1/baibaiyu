package com.gmd.faq.server.main.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库类型
 *
 */
@Getter
@AllArgsConstructor
public enum KbType {
    BAILIAN("百炼"),
    DIFY("Dify"),
    RAGFLOW("Ragflow"),
    LIGHTRAG("知识图谱"),
    LOCAL("本地");

    private final String description;
}
