package com.gmd.faq.server.main.tool.openapi;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 中单个接口操作的解析结果（内部模型）
 *
 */
@Getter
@Setter
public class OpenApiOperation {

    /**
     * 文档中的 operationId（可能为空，为空时由 method + path 兜底生成）
     */
    private String operationId;

    /**
     * HTTP 方法（大写）
     */
    private String method;

    /**
     * 接口路径（以 / 开头，保留 {param} 占位）
     */
    private String path;

    /**
     * 接口标题
     */
    private String summary;

    /**
     * 接口描述
     */
    private String description;

    /**
     * 分组标签
     */
    private String tag;

    /**
     * 参数列表
     */
    private List<Param> parameters = new ArrayList<>();

    /**
     * 是否有 JSON 请求体
     */
    private boolean hasBody;

    /**
     * 是否为 JSON 请求体（目前仅支持 application/json）
     */
    private boolean jsonBody;

    /**
     * 接口参数
     */
    @Getter
    @Setter
    public static class Param {

        /**
         * 参数名
         */
        private String name;

        /**
         * 参数位置：path / query / header / body
         */
        private String in;

        /**
         * 是否必填
         */
        private boolean required;

        /**
         * 参数说明
         */
        private String description;

        /**
         * 参数类型（string / number / boolean / array / object）
         */
        private String type = "string";

        /**
         * 默认值
         */
        private String defaultValue;
    }
}
