package com.gmd.faq.server.main.common.vo;

import lombok.Data;

/**
 * OpenAPI 接口操作预览 VO
 *
 */
@Data
public class OpenApiOperationVO {

    /**
     * 文档中的原始 operationId（可能为空）
     */
    private String operationId;

    /**
     * 生成的工具编号（导入后即工具的 toolId）
     */
    private String toolId;

    /**
     * HTTP 方法
     */
    private String method;

    /**
     * 接口路径
     */
    private String path;

    /**
     * 接口标题
     */
    private String summary;

    /**
     * 分组标签
     */
    private String tag;

    /**
     * 参数个数（路径 + 查询 + 请求头 + 请求体字段）
     */
    private Integer paramCount;

    /**
     * 是否包含请求体
     */
    private Boolean hasBody;

    /**
     * 是否已存在同名工具（导入时按更新处理）
     */
    private Boolean exists;
}
