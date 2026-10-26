package com.gmd.faq.server.main.common.dto;

import lombok.Data;

import java.util.List;

/**
 * OpenAPI 导入 DTO
 *
 */
@Data
public class OpenApiImportDTO {

    /**
     * OpenAPI 文档内容（JSON 或 YAML 文本）
     */
    private String spec;

    /**
     * 选中的 operationId 列表；为空表示导入全部
     */
    private List<String> operationIds;

    /**
     * 覆盖文档中的 servers 基地址（可选），留空则用文档里的第一个 server
     */
    private String baseUrl;

    /**
     * 导入后的工具分类（用于工具列表归类）
     */
    private String category;

    /**
     * 统一附加的鉴权请求头名称（如 Authorization、X-Api-Key），可为空
     */
    private String authHeaderName;

    /**
     * 统一附加的鉴权请求头取值（如 Bearer xxx），可为空
     */
    private String authHeaderValue;
}
